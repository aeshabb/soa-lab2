#!/usr/bin/env bash
set -euo pipefail
echo 'Запуск лабораторной: проверяю настройки и сервисы...'
LAB_HOME=$(cd "$(dirname "$0")/.." && pwd)
source "$LAB_HOME/.runtime/settings.sh"
# Настройки Java из интерактивного профиля не должны менять JVM этих серверов.
unset _JAVA_OPTIONS JDK_JAVA_OPTIONS
export BOOKING_SERVICE_URL="https://localhost:$BOOKING_HTTPS_PORT"
export BOOKING_CERTIFICATE="$LAB_HOME/.runtime/payara.crt"
export PATH="$JAVA_HOME/bin:$PATH"
export JAVA_TOOL_OPTIONS='-Xmx128m -XX:ActiveProcessorCount=1 -XX:+UseSerialGC'
WF="$LAB_HOME/runtime/wildfly-35.0.1.Final"
PAYARA="$LAB_HOME/runtime/payara6"
NGINX="$LAB_HOME/runtime/nginx/sbin/nginx"
TICKET_BACKEND_PORT="${TICKET_BACKEND_PORT:-28257}"
TICKET_REDIRECT_PORT="${TICKET_REDIRECT_PORT:-28258}"
[[ -f "$LAB_HOME/artifacts/ticket-service.war" && -f "$LAB_HOME/artifacts/booking-service.war" ]] || {
  echo "Не найдены WAR-файлы в $LAB_HOME/artifacts" >&2; exit 1;
}
[[ -x "$NGINX" && -f "$LAB_HOME/deploy/nginx.conf" ]] || {
  echo "Не найдены nginx или $LAB_HOME/deploy/nginx.conf" >&2; exit 1;
}
for port in "$TICKET_HTTPS_PORT" "$TICKET_BACKEND_PORT" "$TICKET_REDIRECT_PORT"; do
  [[ "$port" =~ ^[0-9]{1,5}$ ]] && ((10#$port > 1023 && 10#$port < 65536)) || {
    echo "Некорректный порт: $port" >&2; exit 1;
  }
done
nginx_changed=false
sed -e "s/@TICKET_HTTPS_PORT@/$TICKET_HTTPS_PORT/g" \
    -e "s/@TICKET_BACKEND_PORT@/$TICKET_BACKEND_PORT/g" \
    -e "s/@TICKET_REDIRECT_PORT@/$TICKET_REDIRECT_PORT/g" \
    "$LAB_HOME/deploy/nginx.conf" > "$LAB_HOME/.runtime/nginx.conf.next"
"$NGINX" -p "$LAB_HOME/" -e "$LAB_HOME/.runtime/nginx.log" -c .runtime/nginx.conf.next -t
if ! cmp -s "$LAB_HOME/.runtime/nginx.conf.next" "$LAB_HOME/.runtime/nginx.conf"; then
  mv "$LAB_HOME/.runtime/nginx.conf.next" "$LAB_HOME/.runtime/nginx.conf"
  nginx_changed=true
else
  rm "$LAB_HOME/.runtime/nginx.conf.next"
fi
ticket_ready() {
  curl --noproxy '*' -fsS --connect-timeout 2 --max-time 3 --cacert "$LAB_HOME/.runtime/wildfly.crt" \
    "https://localhost:${1:-$TICKET_BACKEND_PORT}/tickets" >/dev/null 2>&1
}
if ticket_ready; then
  echo 'WildFly уже работает.'
elif curl --noproxy '*' -s --connect-timeout 2 --max-time 3 --output /dev/null "http://127.0.0.1:$WILDFLY_ADMIN_PORT"; then
  echo 'WildFly уже запущен; ожидаю готовности приложения...'
else
  echo 'Запускаю WildFly...'
  cp "$LAB_HOME/artifacts/ticket-service.war" "$WF/standalone/deployments/ticket-service.war"
  JAVA_OPTS='-Xms64m -Xmx384m -XX:ActiveProcessorCount=2 -Djava.net.preferIPv4Stack=true' \
    nohup "$WF/bin/standalone.sh" -b 0.0.0.0 -bmanagement 127.0.0.1 > "$LAB_HOME/.runtime/wildfly.log" 2>&1 < /dev/null &
  wildfly_launcher=$!
fi
echo 'Ожидаю готовности Ticket API (до 90 секунд)...'
ready=false
deadline=$((SECONDS + 90))
next_notice=$((SECONDS + 10))
while ((SECONDS < deadline)); do
  if ticket_ready; then ready=true; break; fi
  if [[ -n "${wildfly_launcher:-}" ]] && ! kill -0 "$wildfly_launcher" 2>/dev/null; then
    echo "WildFly завершился при запуске. Причина в $LAB_HOME/.runtime/wildfly.log" >&2
    exit 1
  fi
  if ((SECONDS >= next_notice)); then
    echo 'WildFly ещё запускается...'
    next_notice=$((SECONDS + 10))
  fi
  sleep 1
done
[[ "$ready" == true ]] || { echo "WildFly не готов за 90 секунд. См. $LAB_HOME/.runtime/wildfly.log" >&2; exit 1; }
echo 'Ticket API готов.'
echo 'Проверяю HTTPS-вход и перенаправление HTTP...'
nginx_pid=''
[[ ! -f "$LAB_HOME/.runtime/nginx.pid" ]] || nginx_pid=$(< "$LAB_HOME/.runtime/nginx.pid")
if [[ "$nginx_pid" =~ ^[0-9]+$ ]] && kill -0 "$nginx_pid" 2>/dev/null; then
  if [[ "$nginx_changed" == true ]]; then
    "$NGINX" -p "$LAB_HOME/" -e "$LAB_HOME/.runtime/nginx.log" -c .runtime/nginx.conf -s reload
  else
    echo 'nginx уже работает.'
  fi
else
  "$NGINX" -p "$LAB_HOME/" -e "$LAB_HOME/.runtime/nginx.log" -c .runtime/nginx.conf
fi
gateway_ready=false
for ((i=0;i<5;i++)); do
  redirect_status=$(curl --noproxy '*' -s --connect-timeout 2 --max-time 3 -o /dev/null -w '%{http_code}' \
    "http://localhost:$TICKET_HTTPS_PORT/" || true)
  if [[ "$redirect_status" == 308 ]] && ticket_ready "$TICKET_HTTPS_PORT" && \
      curl --noproxy '*' -fsS --connect-timeout 2 --max-time 3 --cacert "$LAB_HOME/.runtime/wildfly.crt" \
        "https://localhost:$TICKET_HTTPS_PORT/" >/dev/null 2>&1; then
    gateway_ready=true; break
  fi
  sleep 1
done
[[ "$gateway_ready" == true ]] || {
  echo "HTTPS-вход или HTTP-редирект недоступен. См. $LAB_HOME/.runtime/nginx.log" >&2; exit 1;
}
echo 'HTTP перенаправляется на HTTPS; клиент и API доступны только по TLS.'
booking_ready() {
  [[ "$(curl --noproxy '*' -s --connect-timeout 2 --max-time 3 --cacert "$LAB_HOME/.runtime/payara.crt" -o /dev/null -w '%{http_code}' \
    -X POST "https://localhost:$BOOKING_HTTPS_PORT/booking/person/0/cancel" || true)" == 400 ]]
}
# Уже работающий Booking не требует повторного развёртывания и перезапуска.
if booking_ready; then
  echo 'Payara и Booking уже работают.'
else
  echo 'Запускаю Payara и проверяю приложение Booking...'
  if ! curl --noproxy '*' -s --connect-timeout 2 --max-time 3 --output /dev/null "http://localhost:$PAYARA_ADMIN_PORT"; then
    "$PAYARA/bin/asadmin" --port "$PAYARA_ADMIN_PORT" start-domain domain1
  fi
  applications=$("$PAYARA/bin/asadmin" --terse=true --port "$PAYARA_ADMIN_PORT" list-applications)
  if awk '$1 == "booking-service" { found = 1 } END { exit !found }' <<< "$applications"; then
    echo 'Обновляю развёртывание Booking...'
    # Горячий --force оставляет старый модуль в корневом контексте Payara.
    # Убираем регистрацию и запускаем домен заново перед установкой WAR.
    "$PAYARA/bin/asadmin" --port "$PAYARA_ADMIN_PORT" undeploy booking-service
    payara_pid=$(< "$PAYARA/glassfish/domains/domain1/config/pid")
    "$PAYARA/bin/asadmin" --port "$PAYARA_ADMIN_PORT" stop-domain domain1
    # Ждём завершения процесса перед запуском нового JVM.
    for ((i=0;i<30;i++)); do
      kill -0 "$payara_pid" 2>/dev/null || break
      sleep 1
    done
    if kill -0 "$payara_pid" 2>/dev/null; then echo 'Payara ещё не завершилась'; exit 1; fi
    "$PAYARA/bin/asadmin" --port "$PAYARA_ADMIN_PORT" start-domain domain1
  fi
  "$PAYARA/bin/asadmin" --port "$PAYARA_ADMIN_PORT" deploy --contextroot / --name booking-service "$LAB_HOME/artifacts/booking-service.war"
fi
echo 'Ожидаю готовности Booking API (до 60 секунд)...'
ready=false
deadline=$((SECONDS + 60))
next_notice=$((SECONDS + 10))
while ((SECONDS < deadline)); do
  if booking_ready; then ready=true; break; fi
  if ((SECONDS >= next_notice)); then
    echo 'Booking ещё запускается...'
    next_notice=$((SECONDS + 10))
  fi
  sleep 1
done
[[ "$ready" == true ]] || { echo "Booking не готов за 60 секунд. См. $PAYARA/glassfish/domains/domain1/logs/server.log" >&2; exit 1; }
echo 'Оба сервиса готовы.'
echo "Ticket API: https://helios.cs.ifmo.ru:$TICKET_HTTPS_PORT/tickets"
echo "Клиент: https://helios.cs.ifmo.ru:$TICKET_HTTPS_PORT/"
echo "Booking API: https://helios.cs.ifmo.ru:$BOOKING_HTTPS_PORT/booking/..."

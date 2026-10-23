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
[[ -f "$LAB_HOME/artifacts/ticket-service.war" && -f "$LAB_HOME/artifacts/booking-service.war" ]] || {
  echo "Не найдены WAR-файлы в $LAB_HOME/artifacts" >&2; exit 1;
}
ticket_ready() {
  curl --noproxy '*' -fsS --connect-timeout 2 --max-time 3 --cacert "$LAB_HOME/.runtime/wildfly.crt" \
    "https://localhost:$TICKET_HTTPS_PORT/tickets" >/dev/null 2>&1
}
if ticket_ready; then
  echo 'WildFly уже работает.'
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

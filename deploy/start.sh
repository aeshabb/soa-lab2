#!/usr/bin/env bash
set -euo pipefail
LAB_HOME=$(cd "$(dirname "$0")/.." && pwd)
source "$LAB_HOME/.runtime/settings.sh"
export PATH="$JAVA_HOME/bin:$PATH"
export JAVA_TOOL_OPTIONS='-Xmx128m -XX:ActiveProcessorCount=2'
WF="$LAB_HOME/runtime/wildfly-35.0.1.Final"
PAYARA="$LAB_HOME/runtime/payara6"
[[ -f "$LAB_HOME/artifacts/ticket-service.war" && -f "$LAB_HOME/artifacts/booking-service.war" ]]
if ! curl -fsS --cacert "$LAB_HOME/.runtime/wildfly.crt" "https://localhost:$TICKET_HTTPS_PORT/tickets" >/dev/null 2>&1; then
  cp "$LAB_HOME/artifacts/ticket-service.war" "$WF/standalone/deployments/ticket-service.war"
  JAVA_OPTS='-Xms64m -Xmx384m -XX:ActiveProcessorCount=2 -Djava.net.preferIPv4Stack=true' \
    nohup "$WF/bin/standalone.sh" -b 0.0.0.0 -bmanagement 127.0.0.1 > "$LAB_HOME/.runtime/wildfly.log" 2>&1 < /dev/null &
fi
ready=false
for ((i=0;i<90;i++)); do
  if curl -fsS --cacert "$LAB_HOME/.runtime/wildfly.crt" "https://localhost:$TICKET_HTTPS_PORT/tickets" >/dev/null 2>&1; then ready=true; break; fi
  sleep 1
done
[[ "$ready" == true ]] || { echo 'WildFly не запустился. См. .runtime/wildfly.log'; exit 1; }
if ! curl -s --max-time 2 --output /dev/null "http://localhost:$PAYARA_ADMIN_PORT"; then
  "$PAYARA/bin/asadmin" --port "$PAYARA_ADMIN_PORT" start-domain domain1
fi
applications=$("$PAYARA/bin/asadmin" --terse=true --port "$PAYARA_ADMIN_PORT" list-applications)
if awk '$1 == "booking-service" { found = 1 } END { exit !found }' <<< "$applications"; then
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
echo "Ticket API: https://helios.cs.ifmo.ru:$TICKET_HTTPS_PORT/tickets"
echo "Клиент и Booking API: https://helios.cs.ifmo.ru:$BOOKING_HTTPS_PORT/"

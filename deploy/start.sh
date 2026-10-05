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
"$PAYARA/bin/asadmin" --port "$PAYARA_ADMIN_PORT" start-domain domain1
"$PAYARA/bin/asadmin" --port "$PAYARA_ADMIN_PORT" deploy --force=true --contextroot / --name booking-service "$LAB_HOME/artifacts/booking-service.war"
echo "Ticket API: https://helios.cs.ifmo.ru:$TICKET_HTTPS_PORT/tickets"
echo "Клиент и Booking API: https://helios.cs.ifmo.ru:$BOOKING_HTTPS_PORT/"

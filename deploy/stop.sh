#!/usr/bin/env bash
set -euo pipefail
LAB_HOME=$(cd "$(dirname "$0")/.." && pwd)
source "$LAB_HOME/.runtime/settings.sh"
export JAVA_TOOL_OPTIONS='-Xmx128m -XX:ActiveProcessorCount=2'
if curl -s --max-time 2 --output /dev/null "http://localhost:$PAYARA_ADMIN_PORT"; then
  "$LAB_HOME/runtime/payara6/bin/asadmin" --port "$PAYARA_ADMIN_PORT" stop-domain domain1
fi
if curl -s --max-time 2 --output /dev/null "http://localhost:$WILDFLY_ADMIN_PORT"; then
  JAVA_OPTS='-Xmx128m' "$LAB_HOME/runtime/wildfly-35.0.1.Final/bin/jboss-cli.sh" \
    --connect --controller="localhost:$WILDFLY_ADMIN_PORT" --command=':shutdown'
fi

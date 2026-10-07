#!/usr/bin/env bash
set -euo pipefail
LAB_HOME=$(cd "$(dirname "$0")/.." && pwd)
export JAVA_HOME=${JAVA_HOME:-/usr/local/openjdk17}
export AS_JAVA="$JAVA_HOME"
export JAVA_TOOL_OPTIONS='-Xmx128m -XX:ActiveProcessorCount=2'
export PATH="$JAVA_HOME/bin:$PATH"
PYTHON=${PYTHON:-/usr/local/bin/python3.11}
umask 077
mkdir -p "$LAB_HOME/runtime" "$LAB_HOME/.runtime" "$LAB_HOME/artifacts"
if [[ -f "$LAB_HOME/.runtime/configured" ]]; then echo 'Уже настроено.'; exit 0; fi
WF="$LAB_HOME/runtime/wildfly-35.0.1.Final"
PAYARA="$LAB_HOME/runtime/payara6"
# Потоковая распаковка экономит место в квоте Helios; tar на FreeBSD читает ZIP.
if [[ ! -d "$WF" ]]; then
  curl -fsSL https://github.com/wildfly/wildfly/releases/download/35.0.1.Final/wildfly-35.0.1.Final.zip | tar -xf - -C "$LAB_HOME/runtime"
fi
if [[ ! -d "$PAYARA" ]]; then
  curl -fsSL https://repo.maven.apache.org/maven2/fish/payara/distributions/payara/6.2025.1/payara-6.2025.1.zip | tar -xf - -C "$LAB_HOME/runtime"
fi
chmod u+x "$PAYARA/bin/asadmin" "$PAYARA/glassfish/bin/asadmin"
TICKET_HTTPS_PORT=${TICKET_HTTPS_PORT:-28256}
BOOKING_HTTPS_PORT=${BOOKING_HTTPS_PORT:-29256}
WILDFLY_ADMIN_PORT=${WILDFLY_ADMIN_PORT:-28290}
PAYARA_ADMIN_PORT=${PAYARA_ADMIN_PORT:-29248}
KEYSTORE_PASSWORD=$(openssl rand -hex 16)
SAN='SAN=dns:helios.cs.ifmo.ru,dns:se.ifmo.ru,dns:localhost,ip:127.0.0.1'
keytool -genkeypair -alias soa-wildfly -keyalg RSA -keysize 2048 -validity 365 \
  -dname 'CN=helios.cs.ifmo.ru, O=ITMO Lab2' -ext "$SAN" \
  -keystore "$LAB_HOME/.runtime/wildfly.p12" -storetype PKCS12 -storepass "$KEYSTORE_PASSWORD"
keytool -exportcert -rfc -alias soa-wildfly -keystore "$LAB_HOME/.runtime/wildfly.p12" \
  -storepass "$KEYSTORE_PASSWORD" -file "$LAB_HOME/.runtime/wildfly.crt"
keytool -importcert -noprompt -alias soa-wildfly -file "$LAB_HOME/.runtime/wildfly.crt" \
  -keystore "$LAB_HOME/.runtime/ticket-trust.p12" -storetype PKCS12 -storepass "$KEYSTORE_PASSWORD"
keytool -genkeypair -alias soa-payara -keyalg RSA -keysize 2048 -validity 365 \
  -dname 'CN=helios.cs.ifmo.ru, O=ITMO Lab2' -ext "$SAN" \
  -keystore "$PAYARA/glassfish/domains/domain1/config/keystore.p12" -storetype PKCS12 -storepass changeit
keytool -exportcert -rfc -alias soa-payara -keystore "$PAYARA/glassfish/domains/domain1/config/keystore.p12" \
  -storepass changeit -file "$LAB_HOME/.runtime/payara.crt"
cat > "$LAB_HOME/.runtime/settings.sh" <<EOF
export JAVA_HOME='$JAVA_HOME'
export AS_JAVA='$JAVA_HOME'
export TICKET_HTTPS_PORT='$TICKET_HTTPS_PORT'
export BOOKING_HTTPS_PORT='$BOOKING_HTTPS_PORT'
export WILDFLY_ADMIN_PORT='$WILDFLY_ADMIN_PORT'
export PAYARA_ADMIN_PORT='$PAYARA_ADMIN_PORT'
export TICKET_SERVICE_URL='https://localhost:$TICKET_HTTPS_PORT'
export TICKET_TRUSTSTORE='$LAB_HOME/.runtime/ticket-trust.p12'
export TICKET_TRUSTSTORE_PASSWORD='$KEYSTORE_PASSWORD'
EOF
cat > "$LAB_HOME/.runtime/configure-wildfly.cli" <<EOF
embed-server --server-config=standalone.xml --std-out=discard
batch
/subsystem=elytron/key-store=applicationKS:write-attribute(name=path,value="$LAB_HOME/.runtime/wildfly.p12")
/subsystem=elytron/key-store=applicationKS:undefine-attribute(name=relative-to)
/subsystem=elytron/key-store=applicationKS:write-attribute(name=type,value=PKCS12)
/subsystem=elytron/key-store=applicationKS:write-attribute(name=credential-reference,value={clear-text="$KEYSTORE_PASSWORD"})
/subsystem=elytron/key-manager=applicationKM:write-attribute(name=credential-reference,value={clear-text="$KEYSTORE_PASSWORD"})
/subsystem=elytron/key-manager=applicationKM:undefine-attribute(name=generate-self-signed-certificate-host)
/subsystem=elytron/server-ssl-context=applicationSSC:write-attribute(name=protocols,value=[TLSv1.2,TLSv1.3])
/subsystem=remoting/http-connector=http-remoting-connector:write-attribute(name=connector-ref,value=https)
/subsystem=undertow/server=default-server/http-listener=default:remove
/socket-binding-group=standard-sockets/socket-binding=https:write-attribute(name=port,value=$TICKET_HTTPS_PORT)
/socket-binding-group=standard-sockets/socket-binding=management-http:write-attribute(name=port,value=$WILDFLY_ADMIN_PORT)
run-batch
stop-embedded-server
EOF
JAVA_OPTS='-Xmx256m -XX:ActiveProcessorCount=2' "$WF/bin/jboss-cli.sh" --file="$LAB_HOME/.runtime/configure-wildfly.cli"
"$PYTHON" "$LAB_HOME/deploy/configure-payara.py" "$PAYARA/glassfish/domains/domain1/config/domain.xml" "$BOOKING_HTTPS_PORT" "$PAYARA_ADMIN_PORT"
"$PYTHON" "$LAB_HOME/deploy/configure-postgres.py" "$LAB_HOME"
touch "$LAB_HOME/.runtime/configured"
echo 'Настройка завершена. Скопируйте WAR в artifacts/ и запустите deploy/start.sh.'

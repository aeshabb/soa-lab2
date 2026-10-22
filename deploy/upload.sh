#!/usr/bin/env bash
set -euo pipefail
REMOTE=${1:?Использование: deploy/upload.sh sNNNNNN@helios.cs.ifmo.ru}
LAB_HOME=$(cd "$(dirname "$0")/.." && pwd)
cd "$LAB_HOME"
mvn -q package
ssh -p 2222 "$REMOTE" 'mkdir -p ~/soa-lab2/artifacts ~/soa-lab2/deploy'
# Keep the full sources and regression checks on Helios as well as the WARs.
tar --exclude=.git --exclude=target --exclude=.runtime --exclude=runtime \
  --exclude=artifacts --exclude=backups -czf - \
  pom.xml README.md .gitignore common ticket-service booking-service spec certs deploy tests | \
  ssh -p 2222 "$REMOTE" 'mkdir -p ~/soa-lab2/source && tar -xzf - -C ~/soa-lab2/source'
scp -P 2222 deploy/install.sh deploy/start.sh deploy/stop.sh deploy/status.sh deploy/configure-payara.py deploy/configure-postgres.py deploy/postgres.sql "$REMOTE:soa-lab2/deploy/"
ssh -p 2222 "$REMOTE" 'if test -f ~/soa-lab2/.runtime/configured; then bash ~/soa-lab2/deploy/stop.sh; fi'
scp -P 2222 ticket-service/target/ticket-service.war booking-service/target/booking-service.war "$REMOTE:soa-lab2/artifacts/"
ssh -p 2222 "$REMOTE" 'bash ~/soa-lab2/deploy/install.sh && /usr/local/bin/python3.11 ~/soa-lab2/deploy/configure-postgres.py ~/soa-lab2 && bash ~/soa-lab2/deploy/start.sh'

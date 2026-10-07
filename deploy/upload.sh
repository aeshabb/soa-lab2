#!/usr/bin/env bash
set -euo pipefail
REMOTE=${1:?Использование: deploy/upload.sh sNNNNNN@helios.cs.ifmo.ru}
LAB_HOME=$(cd "$(dirname "$0")/.." && pwd)
cd "$LAB_HOME"
mvn -q package
ssh -p 2222 "$REMOTE" 'mkdir -p ~/soa-lab2/artifacts ~/soa-lab2/deploy'
scp -P 2222 deploy/install.sh deploy/start.sh deploy/stop.sh deploy/configure-payara.py "$REMOTE:soa-lab2/deploy/"
ssh -p 2222 "$REMOTE" 'if test -f ~/soa-lab2/.runtime/configured; then bash ~/soa-lab2/deploy/stop.sh; fi'
scp -P 2222 ticket-service/target/ticket-service.war booking-service/target/booking-service.war "$REMOTE:soa-lab2/artifacts/"
ssh -p 2222 "$REMOTE" 'bash ~/soa-lab2/deploy/install.sh && bash ~/soa-lab2/deploy/start.sh'

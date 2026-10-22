#!/usr/bin/env bash
set -euo pipefail
LAB_HOME=$(cd "$(dirname "$0")/.." && pwd)
ps -ww -U "$(id -un)" -o pid= -o args= | awk \
  -v wf="$LAB_HOME/runtime/wildfly-35.0.1.Final" \
  -v payara="$LAB_HOME/runtime/payara6" '
  $2 ~ /(^|\/)java$/ && index($0, wf) { print "Ticket / WildFly: PID=" $1; ticket=1 }
  $2 ~ /(^|\/)java$/ && index($0, payara) { print "Booking / Payara: PID=" $1; booking=1 }
  END {
    if (!ticket) print "Ticket / WildFly: stopped"
    if (!booking) print "Booking / Payara: stopped"
  }'

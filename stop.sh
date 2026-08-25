#!/usr/bin/env bash
# ============================================================================
# stop.sh — stop (and restart) the example-rabbit system:
#   docker compose stack, dev servers (ng serve / backend jars) and colima.
#
#   ./stop.sh               stop everything (stack + dev servers + colima)
#   ./stop.sh stack         stop only the docker compose stack
#   ./stop.sh dev           kill only the dev servers
#   ./stop.sh colima        stop only the colima VM
#   ./stop.sh restart       stop everything, then start it again (start.sh)
#   ./stop.sh status        show what is running
# ============================================================================
set -euo pipefail

ROOT="$(cd "$(dirname "$0")" && pwd)"
cd "$ROOT"

log()  { echo -e "\033[1;34m[stop]\033[0m $*"; }

compose_running() {
  command -v docker >/dev/null 2>&1 || return 1
  docker compose ps -q >/dev/null 2>&1 && [ -n "$(docker compose ps -q 2>/dev/null)" ]
}

stop_stack() {
  if compose_running; then
    log "Stopping docker compose stack..."
    docker compose down
    log "Stack stopped."
  else
    log "No compose containers running."
  fi
}

stop_dev() {
  local found=0
  if pkill -f "ng serve" 2>/dev/null; then log "Stopped ng serve"; found=1; fi
  if pkill -f "source-changer-0.0.1-SNAPSHOT.jar" 2>/dev/null; then log "Stopped backend jar"; found=1; fi
  if pkill -f "gateway-0.0.1-SNAPSHOT.jar" 2>/dev/null; then log "Stopped gateway jar"; found=1; fi
  [ "$found" = 0 ] && log "No dev servers running."
}

stop_colima() {
  if command -v colima >/dev/null 2>&1 && colima status >/dev/null 2>&1; then
    log "Stopping colima VM..."
    colima stop
    log "Colima stopped."
  else
    log "Colima not running."
  fi
}

status() {
  log "docker compose:"
  docker compose ps 2>/dev/null || echo "  (docker not available / stack not up)"
  log "dev servers:"
  pgrep -fl "ng serve|source-changer-0.0.1-SNAPSHOT.jar|gateway-0.0.1-SNAPSHOT.jar" || echo "  (none)"
  log "colima:"
  colima status 2>/dev/null | head -1 || echo "  (not running)"
}

case "${1:-all}" in
  all)     stop_stack; stop_dev; stop_colima; log "All stopped." ;;
  stack)   stop_stack ;;
  dev)     stop_dev ;;
  colima)  stop_colima ;;
  restart) stop_stack; stop_dev; stop_colima; log "Starting again..."; "$ROOT/start.sh" ;;
  status)  status ;;
  *)       echo "Usage: $0 [all|stack|dev|colima|restart|status]"; exit 1 ;;
esac

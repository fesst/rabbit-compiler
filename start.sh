#!/usr/bin/env bash
# ============================================================================
# start.sh — build & run the example-rabbit stack (RabbitMQ + source-changer +
# web-ui) on Colima, exposed at http://192.168.1.125:4200
#
#   ./start.sh            run the whole stack
#   ./start.sh stop       stop and remove the stack containers
#   ./start.sh logs       tail logs of the stack
#   ./start.sh status     show stack status
# ============================================================================
set -euo pipefail

ROOT="$(cd "$(dirname "$0")" && pwd)"
cd "$ROOT"

HOST_IP="192.168.1.125"
WEB_UI_PORT="4200"
URL="http://${HOST_IP}:${WEB_UI_PORT}"

log()  { echo -e "\033[1;34m[start]\033[0m $*"; }
fail() { echo -e "\033[1;31m[start] ERROR:\033[0m $*" >&2; exit 1; }

# --- actions -----------------------------------------------------------------
case "${1:-up}" in
  stop)
    "$ROOT/stop.sh" stack
    exit 0
    ;;
  dev)
    "$0"
    log "Starting Angular dev server on http://localhost:8101 ..."
    nohup bash -c 'cd "$1/web-ui" && npx ng serve --port 8101 --proxy-config proxy.conf.json' _ "$ROOT" > /tmp/ngserve.log 2>&1 &
    echo "  ng serve pid: $!  (log: /tmp/ngserve.log)"
    for _ in $(seq 1 60); do
      if curl -s -o /dev/null --max-time 2 http://localhost:8101/ 2>/dev/null; then
        log "✅ Dev server up: http://localhost:8101 (proxies /api and /ws to the docker backend)"
        exit 0
      fi
      sleep 1
    done
    fail "Dev server did not come up; check /tmp/ngserve.log"
    ;;
  logs)
    docker compose logs -f --tail=100
    exit 0
    ;;
  status)
    docker compose ps
    exit 0
    ;;
esac

# --- 1. docker CLI present? ---------------------------------------------------
if ! command -v docker >/dev/null 2>&1; then
  log "docker CLI not found, installing via Homebrew..."
  brew install docker || fail "brew install docker failed"
fi

# --- 2. Colima VM running? start it (idempotent) ------------------------------
if ! colima status >/dev/null 2>&1; then
  log "Colima not running, starting VM (4 CPU / 8 GB / 50 GB)..."
  colima start --cpu 4 --memory 8 --disk 50 || fail "colima start failed"
else
  log "Colima already running"
fi

# --- 3. Use the colima docker context -----------------------------------------
docker context use colima >/dev/null 2>&1 || true

# --- 4. Build & start the stack ------------------------------------------------
log "Building images (first run downloads Maven/Node base images, be patient)..."
docker compose up -d --build || fail "docker compose up failed"

# --- 5. Wait for the web UI to answer -------------------------------------------
log "Waiting for web UI at ${URL} ..."
for _ in $(seq 1 90); do
  if curl -s -o /dev/null --max-time 2 "${URL}/" 2>/dev/null; then
    log "✅ Stack is up: ${URL}"
    echo
    docker compose ps
    echo
    log "Open ${URL} in your browser (or from other LAN devices)."
    exit 0
  fi
  sleep 2
done

fail "Timed out waiting for ${URL}. Inspect with: ./start.sh logs"

#!/usr/bin/env bash
# Boots the backend jar and the Angular dev server for Playwright e2e.
# Rebuilds the backend jar when sources are newer. Playwright starts this
# (or reuses an already-running stack on :8101).
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
JAR="$ROOT/source-changer/target/source-changer-0.0.1-SNAPSHOT.jar"
MVN="${MVN:-$(command -v mvn || true)}"
MVN="${MVN:-/home/fesst/.local/opt/apache-maven-3.9.9/bin/mvn}"

if [ ! -f "$JAR" ] || find "$ROOT/source-changer/src" "$ROOT/common-rabbitmq/src" -newer "$JAR" -print -quit | grep -q .; then
  echo "[e2e] building backend jar..." >&2
  (cd "$ROOT" && "$MVN" -q -pl source-changer -am package -DskipTests)
fi

WS_DIR="$(mktemp -d)"
echo "[e2e] backend workspace dir: $WS_DIR" >&2
# example.worker.local=true: real in-process compilation (maven) without a broker
java -jar "$JAR" \
  --example.workspace.dir="$WS_DIR" \
  --example.worker.local=true \
  --example.worker.maven-path="$MVN" > "$WS_DIR/backend.log" 2>&1 &
BACKEND_PID=$!

for _ in $(seq 1 60); do
  code="$(curl -s -o /dev/null -w '%{http_code}' -X POST --max-time 2 http://localhost:8100/api/workspaces 2>/dev/null || true)"
  if [ "$code" != "000" ]; then break; fi
  sleep 1
done

cd "$ROOT/web-ui"
npx ng serve --port 8101 --proxy-config proxy.conf.json > "$WS_DIR/ng.log" 2>&1 &
NG_PID=$!
trap 'kill "$BACKEND_PID" "$NG_PID" 2>/dev/null || true' EXIT

for _ in $(seq 1 120); do
  if curl -s -o /dev/null --max-time 2 http://localhost:8101/ 2>/dev/null; then break; fi
  sleep 1
done
wait

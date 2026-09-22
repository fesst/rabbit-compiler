#!/usr/bin/env bash
set -uo pipefail

ROOT="$(cd "$(dirname "$0")" && pwd)"
cd "$ROOT"

CHROME_BIN="${CHROME_BIN:-}"
if [[ -z "$CHROME_BIN" ]]; then
  for candidate in \
    "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome" \
    /usr/bin/google-chrome-stable \
    /usr/bin/google-chrome \
    /usr/bin/chromium; do
    if [[ -x "$candidate" ]]; then
      CHROME_BIN="$candidate"
      break
    fi
  done
fi
if [[ -z "$CHROME_BIN" ]]; then
  echo "CHROME_BIN is not set and no Chrome/Chromium was found" >&2
  exit 1
fi
PASS=0
FAIL=0

report() {
  if [ "$2" = "0" ]; then
    echo "✅ $1"
    PASS=$((PASS + 1))
  else
    echo "❌ $1"
    FAIL=$((FAIL + 1))
  fi
}

echo "=== [1/3] Backend: mvn test (common-rabbitmq + source-changer + worker) ==="
docker run --rm -v "$ROOT":/build -w /build maven:3.9-eclipse-temurin-17 \
  mvn -pl source-changer,worker -am test > /tmp/testall-backend.log 2>&1
BACKEND_OK=$?
grep -E "Tests run: [0-9]+, Failures" /tmp/testall-backend.log | tail -4
grep -E "BUILD (SUCCESS|FAILURE)" /tmp/testall-backend.log | tail -1
report "backend mvn test" $BACKEND_OK

echo "=== [2/3] Frontend unit: Karma/Jasmine ==="
if [ ! -d web-ui/node_modules ]; then
  echo "(installing web-ui deps first)"
  (cd web-ui && npm ci) > /tmp/testall-npm.log 2>&1
fi
(cd web-ui && CHROME_BIN="$CHROME_BIN" npx ng test --watch=false --browsers=ChromeHeadlessNoSandbox) > /tmp/testall-karma.log 2>&1
KARMA_OK=$?
grep -E "TOTAL: [0-9]+" /tmp/testall-karma.log | tail -1
report "frontend karma unit" $KARMA_OK

echo "=== [3/3] E2E: Playwright against the docker stack ==="
if ! docker compose ps --format '{{.Name}}' 2>/dev/null | grep -q source-changer; then
  echo "(stack not running — starting it)"
  ./start.sh > /tmp/testall-start.log 2>&1
fi
pkill -f 'ng serve' 2>/dev/null || true
(cd web-ui && nohup npx ng serve --port 8101 --proxy-config proxy.conf.json > /tmp/testall-ng.log 2>&1 &)
for _ in $(seq 1 90); do
  curl -s -o /dev/null --max-time 2 http://localhost:8101/ 2>/dev/null && break
  sleep 2
done
(cd web-ui && npx playwright test) > /tmp/testall-e2e.log 2>&1
E2E_OK=$?
pkill -f 'ng serve' 2>/dev/null || true
tail -6 /tmp/testall-e2e.log
report "playwright e2e" $E2E_OK

echo
echo "=============================="
echo "SUMMARY: $PASS passed, $FAIL failed"
echo "=============================="
[ "$FAIL" = "0" ]

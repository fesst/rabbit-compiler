#!/usr/bin/env bash
# Zips the current solution (this repository) for use as the web IDE upload
# fixture in the Playwright e2e tests.
#
#   scripts/zip-sources.sh              # write web-ui/e2e/fixtures/sources.zip
#   scripts/zip-sources.sh /tmp/x.zip   # custom output path
#   scripts/zip-sources.sh --check      # access-only: report without writing
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="${2:-$ROOT/web-ui/e2e/fixtures/sources.zip}"
CHECK_MODE=0
if [ "${1:-}" = "--check" ]; then
  CHECK_MODE=1
fi

cd "$ROOT"

if [ "$CHECK_MODE" = "1" ]; then
  count="$(find . -type f \
    -not -path './.git/*' -not -path './.pi/*' -not -path '*/node_modules/*' \
    -not -path '*/target/*' -not -path '*/dist/*' -not -path './workspaces/*' \
    -not -path './web-ui/e2e/*' -not -path './web-ui/.angular/*' \
    -not -name '*.log' -not -name "$(basename "$OUT")" | wc -l)"
  echo "Would zip $count files from $ROOT"
  exit 0
fi

mkdir -p "$(dirname "$OUT")"
rm -f "$OUT"
zip -qr "$OUT" . -x \
  '.git/*' '.pi/*' '*/node_modules/*' '*/target/*' '*/dist/*' \
  'workspaces/*' 'web-ui/e2e/*' 'web-ui/.angular/*' 'web-ui/test-results/*' 'web-ui/playwright-report/*' \
  '*.log' "$(basename "$OUT")"
echo "Wrote $OUT ($(du -h "$OUT" | cut -f1))"

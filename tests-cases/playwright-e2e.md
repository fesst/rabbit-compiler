# Tests: Playwright e2e (frontend, real browser)

Change: `web-ui/e2e/workspace.spec.ts`, `web-ui/playwright.config.ts`,
`scripts/zip-sources.sh` (zips the whole solution as the upload fixture),
`scripts/e2e-server.sh` (boots backend jar + Angular dev server, rebuilds
the jar when sources are newer), `npm run e2e` script, `.gitignore` for
`e2e/fixtures/`, `test-results/`, `playwright-report/`.

## What the spec covers

The real user flow end to end in Chrome (headless, `channel: 'chrome'`):

1. upload `sources.zip` (the zipped current solution, ~230 KB / ~180 files)
   through the top-bar file input;
2. tree arrives over the websocket and the folder rows render;
3. double-click `README.md` opens a tab, Monaco renders the content;
4. typing appends text and the 2 s debounced auto-save updates the footer
   status to `saved README.md`;
5. Ctrl+Space opens the completion widget with real suggestions (local
   worker extracts identifiers from the file);
6. `Save and compile` runs the local worker: real Maven compilation of the
   uploaded solution and a `Compilation OK` success note in the footer;
7. no uncaught console/page errors at any point.

The e2e backend runs with `--example.worker.local=true` (in-process worker,
no broker required): compile/completion are real, the RabbitMQ transport is
not. The broker request/reply path stays covered by the API contract tests.

## Bugs found and fixed by this test

1. The e2e run exposed that the backend dropped WebSocket connections on any
   message carrying a whole file: Spring's default text-message buffer is
   8 KB and the completion request ships the full editor content. The
   browser reconnected (masking the failure; the auto-save silently died
   with the old socket). Fixed by raising the container buffer to 5 MB
   (`ServletServerContainerFactoryBean` in `WebSocketConfig`) and locked
   in with the regression test `webSocketContainerBuffersWholeFiles`.
2. Compiling the uploaded solution for real surfaced a pre-existing
   compilation error in `gateway` (`ScalingService` referenced
   `CapacityMetricName.TOTAL_SOURCE_SIZE_BYTES`, the enum constant is
   `TOTAL_SOURCES_SIZE_BYTES`). `gateway` was never compiled because the
   builds use `-pl source-changer -am`; a full-reactor `mvn test` now
   passes. The worker (Maven or javac) verifies uploaded sources end to end.

The in-process worker (`LocalWorker`, `example.worker.local=true`) added
for the e2e is covered by 6 unit tests (javac success/failure, no sources,
completion dedupe/cap, enabled flag) and 3 handler tests (local compile
success/failure, local completion).

## How to run

    cd web-ui
    npm run e2e                     # zips the solution + runs Playwright
    npx playwright show-trace test-results/<name>/trace.zip   # on failure

Backend and API contract tests stay at 46 (green), JaCoCo coverage checks
still enforced (LINE ~95%, BRANCH ~88%).

## Verification performed

- `npm run e2e` → 1 passed (5.4 s test, 16.3 s incl. server boot).
- Full backend suite after the buffer fix: `Tests run: 46`, all green,
  `All coverage checks have been met`.
- `scripts/zip-sources.sh --check` reports the file count without writing
  (access-only mode); the produced zip excludes git, node_modules, target,
  dist, the Angular build cache, and the e2e artifacts themselves.

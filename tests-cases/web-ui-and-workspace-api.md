# Tests: web-ui + workspace API (REST upload, WebSocket workspace)

Change: Angular web IDE in `web-ui/` + `source-changer` REST upload endpoint
and `/ws/workspace` WebSocket protocol. Frontend tests omitted for now
(user decision).

## Automated tests (backend)

`mvn -pl source-changer -am test` — 45 tests, all green; JaCoCo enforces line and
branch coverage ≥ 80% on every run (currently LINE 95.5%, BRANCH 87.5%):

| Test class                     | Cases | Covers                                                      |
| ------------------------------ | ----- | ----------------------------------------------------------- |
| `WorkspaceStorageTest`        | 12    | zip extraction, tree build/order, read/write, zip-slip + path-escape rejection, extracted-size cap (zip bomb) + cleanup, directory entries skipped, read on directory rejected, oversized file rejected, write creates parents, unknown workspace |
| `WorkspaceControllerTest`     | 4     | REST upload contract: ok, non-zip 400, empty 400, null filename 400 |
| `CompilationServiceTest`      | 6     | success, failure (no retry), MAINTENANCE retry x3, reply failure, interrupted reply restores interrupt flag, cancellation context required |
| `CompletionServiceTest`       | 4     | forwards request, returns suggestions, reply failure, interrupted reply restores interrupt flag |
| `WorkspaceWebSocketHandlerTest` | 17   | protocol contract: subscribe→tree, blank workspaceId, unknown ws error, file→fileContent, file on directory error, save→saved (+disk), compile ok/fail + requestId echo (+no-requestId case), complete suggestions (+no-requestId case), complete failure + requestId echo, closed session gets no replies, disconnect requires re-subscribe, unknown type, malformed payload |
| `WorkspaceApiTest`            | 2     | API contract, FE-independent: REST upload → WS subscribe → file → save → compile → complete; unknown workspace error |

All tests are access-only with respect to the repo: they write exclusively to
JUnit `@TempDir` (system temp), never to project sources. The API test mocks
the RabbitMQ-backed services and disables listener auto-startup, so it runs
without a broker.

## Manual verification performed

1. `npm run build` in `web-ui/` — production bundle OK; Monaco AMD assets
   verified present at `dist/web-ui/browser/assets/monaco/vs`.

Frontend (Karma/Jasmine, Chrome headless): 6 specs for `EditorPanelComponent`
covering the Monaco-readiness race (content buffered until monaco loads),
immediate model creation, the 2 s autosave debounce (incl. restart on new
keystrokes), `saveAllDirty` flush, and dirty-close save. Run with
`cd web-ui && CHROME_BIN=/usr/bin/google-chrome-stable npx ng test --watch=false`.

Review fixes applied after the first review pass:

- `complete()` error path now echoes `requestId` (frontend completion no longer
  waits out its 15 s timeout on server errors); regression test added
  (`completionFailureEchoesRequestId`).
- `WorkspaceWebSocketHandler.send()` skips closed sessions
  (`isOpen()` guard) instead of throwing `IllegalStateException` on a
  closed socket.
- Editor panel subscribes to the workspace stream in the constructor and
  buffers `fileContent` until Monaco finishes loading (files opened before
  Monaco was ready were previously lost, leaving an empty tab); regression
  covered by the Karma spec `buffers fileContent that arrives before Monaco
  is ready`.
- `WorkspaceStorage` gained the extracted-size cap
  (`example.workspace.max-extracted-bytes`, default 100 MB): total
  uncompressed bytes are counted during extraction; exceeding the cap aborts
  and deletes the partial workspace (zip-bomb guard). Covered by
  `extractedSizeCapRejectsZipBombAndCleansUp`.
2. Started `source-changer` jar (JDK 25, no RabbitMQ) — app starts; broker
   connection retried in background only.
3. `curl -F file=@demo.zip :8100/api/workspaces` → `{workspaceId}`. (Ports moved to the 8100 range: backend 8100, dev server 8101.)
4. Node WebSocket client drove the protocol live:
   subscribe → tree → file → fileContent → save → saved → complete
   (completeResult success=false, no worker) → compile (compileResult
   success=false, error propagated) — the no-broker failure path is exactly
   what the footer shows to the user.
5. Saved file verified on server disk.

## How to reproduce

    mvn -pl source-changer -am test
    cd web-ui && npm ci && npm run build

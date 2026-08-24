# example-rabbit

Spring Boot services built around RabbitMQ and autoscaling of long-running worker work.

As an example, rabbit will provide cancellable compliation requests to compile data.
The data for performance reasons won't be passed via protocol for now, but that required compiler
and the source changes to be presented on the same node. Afterwards there were a database storing of sources,
so the data could be consumed from that place but for the completion it was easier to
generate the sources stub-like code and invoke completion since it was non-related to implementations.

## Modules

| Module            | Responsibility                                           |
| ----------------- | -------------------------------------------------------- |
| `common-rabbitmq` | Shared cancellation mechanism and RabbitMQ configuration |
| `source-changer`  | Compilation and completion request/reply clients         |
| `gateway`         | Scaling decision engine                                  |
| `web-ui`          | Angular web IDE (upload zip, workspace over WebSocket)   |

## Web UI (`web-ui/`)

Angular application that turns `source-changer` into a small web IDE:

- **Upload**: a `.zip` of sources is uploaded to `POST /api/workspaces` and
  extracted into a new workspace directory on the server.
- **Workspace over WebSocket**: after the upload the client connects to
  `/ws/workspace` and the whole workspace lives there — tree, file contents,
  saves, compilation and completion are JSON messages
  (`tree`, `fileContent`, `saved`, `compileResult`, `completeResult`, `error`).
- **Layout**: top bar (upload + connection state), left file tree, right
  Monaco editor with tabs, bottom footer with a scrolling notification feed
  and the **Save and compile** button.
- **Editing**: double-click a file in the tree to open it in a tab; every
  change is saved automatically 2 seconds after the last keystroke;
  `Ctrl+Space` triggers the completion popup (Monaco default binding), and
  suggestions come from the server (`CompletionService` over RabbitMQ).
- **Compilation**: the footer button saves all dirty tabs, sends a `compile`
  message and pushes the result — errors included — into the footer
  notification feed, which auto-scrolls.

Run it:

    # backend (source-changer, REST + WebSocket on :8100)
    mvn -pl source-changer -am package
    java -jar source-changer/target/source-changer-0.0.1-SNAPSHOT.jar

    # frontend (Angular dev server on :8101, proxies /api and /ws to :8100)
    cd web-ui
    npm install
    npm start

Compilation and completion run on a worker, in one of two modes:

- `example.worker.local=true` (default off; enabled in the docker stack and
  the e2e): an in-process worker compiles the workspace for real — Maven
  (`mvn -DskipTests compile`, path via `example.worker.maven-path`) for
  pom projects, the JDK compiler otherwise — and answers completion with
  identifiers from the file. No broker needed.
- Off (default): the request/reply path over RabbitMQ
  (`requestCompilation` / `requestCompletion` queues) is used and a
  worker consuming those queues must be deployed (a separate service sharing
  the workspace storage; see the k3s manifests). Without either, compile and
  completion report their failure in the footer.

The e2e suite runs with the local worker and asserts a real, successful
compilation of the uploaded solution.

### Upload limits (the caps)

Three limits protect the server, each configurable:

| Limit                 | Default   | Property / knob                                            | Purpose                                         |
| --------------------- | --------- | ---------------------------------------------------------- | ----------------------------------------------- |
| Upload size           | 100 MB    | `spring.servlet.multipart.max-file-size` / `max-request-size` | bounds the zip itself at the HTTP layer         |
| Extracted size        | 100 MB    | `example.workspace.max-extracted-bytes`                   | zip-bomb guard: total uncompressed bytes are counted during extraction; exceeding the cap aborts the workspace and deletes the partial files |
| Per-file read size    | 5 MB      | `WorkspaceStorage.MAX_EDITABLE_FILE_BYTES` (constant)     | keeps the editor from loading huge files       |

Upload and extracted caps matter most: a small zip can expand to gigabytes,
which would silently fill the disk without the extracted-size cap.

## Docker

    docker compose up --build

The stack (see `docker-compose.yml`):

| Service         | Port (host)        | Notes                                                    |
| --------------- | ------------------ | -------------------------------------------------------- |
| `web-ui`        | 8101               | nginx serves the Angular build, proxies `/api` and `/ws` to the backend |
| `source-changer` | 8100               | Spring Boot; workspaces persisted in the `workspaces` volume |
| `rabbitmq`      | 5672 AMQP / 15672 management UI | broker for compile/completion requests; `guest/guest` login |

Both images build from source: the backend is compiled with JDK 17
(`source-changer/Dockerfile`), the frontend with Node 22
(`web-ui/Dockerfile`). Compilation and completion still need a worker
answering the request queues to return real results.

## Kubernetes (k3s cluster)

The same stack runs on the local k3s cluster (Flux GitOps, Traefik ingress):

- Manifests: `$SIMPLEX/cluster/local/apps/examples/example-rabbit/`
  (namespace `example-rabbit`), see lifetracker
  [req 016](https://git.local/platform/lifetracker/src/branch/master/wiki-it/motley-simplex/reqs/016-example-rabbit-k3s-traefik.md)
  for the cluster implementation (storage, credentials, mirroring).
- Access: `http://rabbit.local` (ingress, needs the `/etc/hosts` entry) or
  `http://localhost:8101` via
  `kubectl -n example-rabbit port-forward svc/web-ui 8101:80`.
- In-cluster the baked `web-ui/nginx.conf` resolves `source-changer:8100`
  via the Service DNS; no frontend changes needed.

## Tests

Backend tests (`mvn -pl source-changer -am test`):

- unit tests per unit: `WorkspaceStorageTest`, `WorkspaceControllerTest`,
  `CompilationServiceTest`, `CompletionServiceTest`,
  `WorkspaceWebSocketHandlerTest` (WebSocket protocol contract)
- API contract tests, independent of the frontend: `WorkspaceApiTest`
  uploads a zip over REST and drives the WebSocket protocol exactly like the
  UI does (RabbitMQ-backed services mocked). If the UI breaks while this test
  stays green, the problem is frontend-only; if it breaks, the backend
  contract changed.
- Coverage is enforced on every `mvn test` run by JaCoCo (line and branch
  ≥ 80% on the `source-changer` bundle; currently ~95% line / ~88% branch).

Frontend: 6 Karma/Jasmine specs cover the editor panel (Monaco readiness
race, autosave debounce, save-all flush, dirty-close).

End-to-end (Playwright, real browser): `cd web-ui && npm run e2e` zips the
whole solution with `scripts/zip-sources.sh` (the fixture the test uploads),
boots backend + dev server via `scripts/e2e-server.sh`, and drives the real
flow in Chrome: upload → tree over the websocket → double-click → edit →
2 s auto-save → Ctrl+Space → compile error in the footer. Config:
`web-ui/playwright.config.ts` (uses the system Chrome via `channel: 'chrome'`).

See `tests-cases/web-ui-and-workspace-api.md` for the verification record.

## Request / reply

`CompilationService` and `CompletionService` offload work to a worker service and
wait for the result using `AsyncRabbitTemplate.convertSendAndReceiveAsType`:

```java
AsyncRabbitTemplate.RabbitConverterFuture<CompilationResultDto> future =
        template.convertSendAndReceiveAsType(exchange, routingKey, payload,
                postProcessor, new ParameterizedTypeReference<>() {});
CompilationResultDto result = future.get();
```

Each channel has its own template and receive timeout (`RabbitConfig`). The
routing key selects the target queue: an instance dedicated to a user uses that
user's id, a common instance uses an empty key.

## Cancellation

Only one request per `(type, scope)` may be active. Registering a new request for
the same key cancels the previous one and broadcasts the cancellation over
RabbitMQ so it can be interrupted from any instance.

```java
CancellableRequest request = new CancellableRequest(
        CancellableRequestType.COMPILATION,
        CancellableRequestScope.of(new ResourceId("source-1")),
        UUID.randomUUID(),
        true);

CancellableRequestHolder.doWithNewRequest(cancellationService, request, () -> {
    CancellableRequestHolder.throwIfRequestCancelled("checkpoint");
    return compilationService.compile();
});
```

## Scaling

`gateway` runs a scheduled job that keeps each service pool between min and max
instances based on a capacity metric:

```
NoScaling ──(metric > max)──▶ ScalingUp   ──(new instance | timeout)──▶ NoScaling
NoScaling ──(metric < min)──▶ ScalingDown ──(instance gone | timeout)──▶ NoScaling
```

`ScalingProvider` performs the actual scale up/down (`Noop` or `CloudController`).

## Configuration

`example.*` properties in each service's `application.yml` hold the exchange
names, timeouts, cancellation lifetime and scaling limits.

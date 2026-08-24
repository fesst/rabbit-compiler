# Tests: Docker packaging

Change: `source-changer/Dockerfile`, `web-ui/Dockerfile`,
`web-ui/nginx.conf` (SPA + `/api` + websocket `/ws` proxy),
`docker-compose.yml` (rabbitmq + backend + frontend), `.dockerignore`,
README Docker section.

## Verification performed

No container runtime is available on this machine (docker/podman absent,
nerdctl present without a containerd daemon), so image builds were not
executed here. The configuration was verified statically:

1. `docker-compose.yml` parses with `yaml.safe_load`; services
   `rabbitmq`, `source-changer`, `web-ui` and the `workspaces` volume
   resolve; env overrides `SPRING_RABBITMQ_HOST` and
   `EXAMPLE_WORKSPACE_DIR` present (Spring relaxed binding maps both).
2. Every path referenced by the Dockerfiles exists:
   `source-changer/target/source-changer-0.0.1-SNAPSHOT.jar` (built by the
   maven stage itself), `web-ui/package-lock.json` (for `npm ci`),
   `dist/web-ui/browser` (the Angular application-builder output copied
   into nginx, verified earlier by `npm run build`).
3. nginx.conf reviewed: `/api/` proxies REST; `/ws` sets
   `Upgrade`/`Connection` headers for the WebSocket handshake with a long
   `proxy_read_timeout`; SPA fallback `try_files … /index.html`.
4. Backend compiles cleanly with JDK 17 settings (maven build uses the same
   `mvn -pl source-changer -am package` command as CI).

## To run

    docker compose up --build
    # open http://localhost:8101, upload a zip, edit, Save and compile

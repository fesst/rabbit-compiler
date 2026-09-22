# AGENTS.md — rabbit-compiler

Spring Boot services around RabbitMQ with autoscaled long-running compile/completion
workers, plus a small Angular web IDE.

Workspace rules: `$TESS/agentic-rules/AGENTS.md`. Docs and reqs live in `$LT/wiki-it/`
(project request: `wiki-it/motley-simplex/reqs/016-example-rabbit-k3s-traefik.md`), never in
this repository; reports go to `$LT/wiki-it/reports/`, tests to
`$LT/wiki-it/tests-cases/`.

## Rules

1. No comments in any file. The test suites are described in `README.md` and in
   `tests-cases/`; that is where explanations belong.
2. Modules are orthogonal: `gateway` decides scaling, `source-changer` performs work,
   `common-rabbitmq` holds the cancellation contract, `web-ui` is the only client. A change
   in one protocol must update its contract tests in the same commit.
3. `./test-all.sh` runs all three suites (backend `mvn test` with the JaCoCo gate in a Maven
   container, Karma unit tests, Playwright e2e). `CHROME_BIN` is honoured when set and
   auto-detected otherwise, so the same script runs on macOS and Linux.
4. CI builds and pushes through the in-cluster Gitea registry; the retired `git.local` name
   must not reappear in scripts, manifests or links.
5. No spaces in file or directory names.

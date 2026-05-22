# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this repo is

End-to-end demo of a three-stage approval workflow built on **Quarkus Flow** (CNCF Serverless Workflow DSL 1.0.0) with a generic *human task* REST surface and an Angular front-end. The workflow is the source of truth; clients only ever complete or cancel tasks regardless of which stage the workflow sits in.

## Stack snapshot

- Backend: **Quarkus 3.33.1** on **Java 25** (LTS) — REST + WebSocket + JPA + Scheduler.
- Workflow engine: `io.quarkiverse.flow:quarkus-flow` 0.9.0 on top of `io.serverlessworkflow` 7.21. The CNCF SW BOM is **overridden to 7.21.1.Final** in `backend/pom.xml` ahead of `quarkus-bom`, because the older 7.18.x that quarkus-flow 0.9.0 ships transitively lacks the `FuncDSL.tryCatch` API used by `ApprovalWorkflow`.
- Frontend: **Angular 21.2** — **zoneless** (`provideZonelessChangeDetection`), standalone components, TypeScript 5.9, **Vitest** + jsdom for tests via `@angular/build:unit-test` (no Karma, no browser).
- Persistence: Hibernate ORM / Panache for app data + `quarkus-flow-jpa` for engine state. **H2 file** dev default (`./.h2/approval`), **H2 in-memory** in `%test`, **PostgreSQL** in `%prod` via `DB_URL` / `DB_USER` / `DB_PASSWORD` env vars.
- Tool versions are pinned in `mise.toml` (`java=temurin-25`, `node=22`, `maven=3.9.11`, `task=3.39.2`).

## Common commands

The canonical entry-point is the `Taskfile.yml`; `task --list` shows everything.

```bash
mise install               # one-time, installs Java 25 / Node 22 / Maven / Task

task dev                   # backend (:8080) + frontend (:4200) in parallel
task dev:backend           # quarkus:dev only
task dev:frontend          # ng serve only

task build                 # mvn package + ng build
task test                  # backend + frontend
task test:backend          # mvn test (10 tests)
task test:frontend         # vitest in jsdom (6 tests)
task e2e                   # Playwright (browser) against a running stack
task e2e:smoke             # legacy curl-based REST smoke test
```

Direct invocations when not using `task`:

```bash
cd backend
mvn -B -ntp test                                           # full backend suite
mvn -B -ntp test -Dtest=TaskExpirationTest                 # single test class
mvn -B -ntp test -Dtest=ApprovalFlowTest#happyPathThroughAllThreeTasks
mvn -B -ntp -e verify                                      # what CI runs
mvn -B -ntp -DskipTests package                            # build the runner JAR

cd frontend
npm test                                                    # vitest --run
npm run build                                              # ng build (prod)
npm run lint                                               # ng lint
```

The runner JAR defaults to the **`prod`** profile, so `java -jar target/quarkus-app/quarkus-run.jar` will try to connect to PostgreSQL on `localhost:5432`. Override the datasource or start it with `-Dquarkus.profile=dev` for local H2 file mode.

## Architecture

### The "generic task" idea (read this first)

`ApprovalWorkflow` is a CNCF Serverless Workflow defined twice:

- **Runtime source of truth** — `backend/src/main/java/com/example/approval/ApprovalWorkflow.java` using `FuncWorkflowBuilder` from the Java SDK.
- **Declarative reference** — `backend/src/main/flow/approval.yaml`.

Every async wait step creates a `HumanTask` via `TaskService.create(...)` and blocks on `task.future()`. REST callers complete or cancel tasks through the same two endpoints (`POST /api/tasks/{id}/complete` and `.../cancel`) — there is **no per-stage REST route**. Adding a new stage to the workflow needs zero changes in the REST layer; the workflow's own `if/else` on `result.outcome()` decides what's valid.

Flow of a single async step:

```
Workflow              TaskService                 REST caller
   |                       |                           |
   |--create(...)--------->|                           |
   |<-- HumanTask ---------|                           |
   |--await(task)----+     |                           |
   |  (blocked on    |     |<--POST /complete----------|
   |   future)       |     |---persist + signal future-|
   |<--TaskResult----+     |                           |
   |  continue             |                           |
```

In `doApproval`, **any non-`APPROVED` outcome** (`REJECTED`, `EXPIRED`, `CANCELLED`) funnels into the rejection branch — that's how the scheduler's `EXPIRED` synthetic outcome drives a request to `REJECTED` through the same code path as explicit rejection.

### Persistence layout

| Data | Owner | Tables |
| --- | --- | --- |
| `ApprovalRequest`, `HumanTask`, `HistoryEntry` (incl. `dueAt` / `reminderAt` / `reminded`) | `ApprovalService` / `TaskService` as `PanacheRepositoryBase` | `approval_request`, `approval_task`, `approval_history` |
| Free-form `context` / `payload` maps | Serialised via `JsonMapConverter` (`AttributeConverter`, Jackson → JSON `TEXT`) | inline column on `approval_task` |
| Workflow engine state | `io.quarkiverse.flow:quarkus-flow-jpa` (CNCF `serverlessworkflow-persistence-api` SPI) | `ProcessInstanceEntity`, `TaskInfoEntity`, `CompletedTaskEntity`, `RetriedTaskEntity` |

The per-task `CompletableFuture<TaskResult>` the workflow blocks on is **deliberately not persisted**. It lives in an in-process `Map<String, CompletableFuture>` inside `TaskService`, keyed by task id. Within a single JVM lifetime that bridges the persisted entity transitions (`complete` / `cancel` / sweep-`expire`) to the workflow's blocking await. Across a restart, a workflow that was mid-await would need to be resumed via an event — not implemented.

### Deadlines, reminders, expiration

`HumanTask` carries `dueAt` and `reminderAt`. `TaskService.sweep()` (Quarkus `@Scheduled`, default `1s`) queries all `PENDING` tasks and:

1. Once `now >= reminderAt` and `!reminded`: marks `reminded=true`, emits `TASK_REMINDER` (task stays PENDING).
2. Once `now >= dueAt`: marks the task `EXPIRED` (`outcome=EXPIRED`, `actor=SYSTEM`), emits `TASK_EXPIRED`, and completes the in-memory future so the workflow unblocks and lands in `REJECTED`.

Configurable in `application.properties`:

| Property | Default |
| --- | --- |
| `app.task.confirmation.timeout` | `PT24H` |
| `app.task.approval.timeout` | `PT48H` |
| `app.task.reminder.offset-fraction` | `0.75` (reminder fires once 75% of the window has elapsed) |
| `app.task.sweep.every` | `1s` |

The `%test` profile overrides leave the defaults long enough that the happy/reject tests are race-free; `TaskExpirationTest` drops them to 6s via its own `QuarkusTestProfile`.

### REST surface

All `/api/*` resource methods are `@Transactional` so the lazy `history` collection is reachable while Jackson builds the DTO. Notable: **don't** name a service method `find(String)` — that shadows Panache's `find(String jpql, ...)` query method. The convention here is `lookup(String id) → Optional<T>` on the services.

WebSocket `/approval-events` pushes `REQUEST_CREATED`, `STATE_CHANGED`, `TASK_CREATED`, `TASK_COMPLETED`, `TASK_REMINDER`, `TASK_EXPIRED`.

### Frontend

- All components are `standalone: true` with explicit `imports`; root bootstrap is via `bootstrapApplication(AppComponent, appConfig)` in `main.ts`.
- Zoneless: `app.config.ts` uses `provideZonelessChangeDetection()`. **Don't reintroduce `zone.js`** as a polyfill — `angular.json` has an empty `polyfills` array and the bundle has dropped to ~327 KB from ~366 KB because of it. Components use signals (`signal()`, `computed()`) for reactive state.
- Test stack: **Vitest** runs in jsdom via `@angular/build:unit-test`. Spec files use explicit `import { describe, it, expect, ... } from 'vitest'` (NOT Jasmine globals). Use `toBe(true)` rather than Jasmine's `toBeTrue()`.
- API base URL is hard-coded to `http://localhost:8080` in `approval.service.ts` — CORS is configured for `http://localhost:4200`.

## Gotchas worth knowing

- **H2 + `MODE=PostgreSQL` breaks `quarkus-flow-jpa`** — its `ProcessInstanceEntity.status` is `TINYINT`, which H2's PostgreSQL compatibility mode rejects. Keep the H2 URL plain (`jdbc:h2:file:./.h2/approval`).
- **CI workflow file structure**: the e2e job spins up a `postgres:17-alpine` service and feeds `DB_URL`/`DB_USER`/`DB_PASSWORD` because the runner JAR defaults to `%prod`. If you change the prod datasource shape, mirror it in `.github/workflows/ci.yml`.
- **E2E is Playwright-driven**: the job builds + runs the Quarkus runner JAR, installs Playwright Chromium (`npx playwright install --with-deps chromium`), and runs `npm run e2e` in `frontend/`. The Playwright config's `webServer` starts `ng serve` on `:4200` itself. Specs live in `frontend/e2e/`; reports + videos + traces are uploaded as artifacts on failure.
- **`@TestProfile`** restarts Quarkus and creates a fresh `%test` H2 in-memory DB (`drop-and-create`), so tests using a custom profile run in their own clean DB. The default `%test` DB URL has `DB_CLOSE_DELAY=-1` so the in-memory DB survives across multiple `@QuarkusTest` classes in the same JVM but is dropped + recreated per Quarkus boot.
- **Two CodeQL languages, both `build-mode: none`** in `.github/workflows/codeql.yml` — don't switch `java-kotlin` to `manual` unless you also re-add the JDK + Maven build step.
- **`dependency-review.yml` is non-blocking** (`continue-on-error: true`) until the repo has GitHub Dependency Graph enabled (Settings → Security). Once that's on, remove the flag.
- **GitHub Actions versions**: actions are pinned to Node-24-compatible majors (`checkout@v6`, `setup-node@v6`, `setup-java@v5`, `upload-artifact@v7`, `codeql-action/*@v4`). Dependabot keeps these grouped under `chore(ci)`.

## Workflow expectations for changes

- Branch convention used by the prior PRs: `claude/<descriptive>-<slug>`.
- CI runs four workflows: `ci.yml` (backend / frontend / e2e / aggregator `ci-success`), `codeql.yml`, `dependency-review.yml`. The aggregator `ci-success` is the single required check — it propagates failures via `needs: [backend, frontend, e2e]`. The e2e job depends on the backend job.
- Dependabot opens grouped PRs weekly (Mon 06:00 Europe/Berlin) for Maven (Quarkus / Quarkiverse / Serverlessworkflow / Maven plugins / test deps), npm (Angular / Karma+Jasmine legacy / TypeScript) and `github-actions`.

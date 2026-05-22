# Quarkus Flow Approval Demo

End-to-end demo of a multi-stage approval workflow:

1. **Async confirmation step** — the requester must confirm their email
   address (via a token-protected link) and explicitly accept the terms
   &amp; conditions. The workflow is *suspended* on this step until the
   click happens.
2. **Approval group 1** — the workflow is suspended until group 1 decides.
3. **Approval group 2** — only entered if group 1 approved; the workflow is
   suspended until group 2 decides.
4. **Terminal** — `APPROVED` if both groups approved, otherwise `REJECTED`.

### End-to-end flow

```mermaid
flowchart LR
    Start([New request]) --> Conf{Confirm<br/>email + terms?}
    Conf -- confirmed --> G1{Group 1<br/>decision?}
    Conf -- cancelled --> Rej([REJECTED])
    G1 -- approve --> G2{Group 2<br/>decision?}
    G1 -- reject --> Rej
    G2 -- approve --> Appr([APPROVED])
    G2 -- reject --> Rej

    classDef terminal fill:#14532d,stroke:#22c55e,color:#bbf7d0
    classDef reject   fill:#7f1d1d,stroke:#ef4444,color:#fecaca
    class Appr terminal
    class Rej reject
```

The demo is structured as **two cooperating sub-workflows**, both
implemented as wait tasks of the same `ApprovalWorkflow`:

1. The **Request workflow** — creates a `CONFIRMATION` task for the
   requester and suspends until the requester either confirms email
   ownership and accepts the terms, or cancels the request.
2. The **Approval workflow** — runs only if confirmation succeeded;
   creates two sequential `APPROVAL` tasks (group 1 first, group 2 only
   if group 1 approved).

#### Request workflow (async email / terms confirmation)

```mermaid
flowchart LR
    Start([Request submitted]) --> CT["HumanTask<br/>type: CONFIRMATION<br/>assignee: REQUESTER<br/>context: { confirmationToken }"]
    CT -- "POST /api/tasks/{id}/complete<br/>outcome = CONFIRMED<br/>payload: { token, termsAccepted }" --> Ok([Hand over to<br/>Approval workflow])
    CT -- "POST /api/tasks/{id}/cancel" --> Rej([REJECTED])
    CT -. "400 - invalid token<br/>or terms not accepted" .-> CT

    classDef task     fill:#1e3a8a,stroke:#38bdf8,color:#bfdbfe
    classDef reject   fill:#7f1d1d,stroke:#ef4444,color:#fecaca
    class CT task
    class Rej reject
```

#### Approval workflow (two sequential approval groups)

```mermaid
flowchart LR
    Start([Confirmed request]) --> G1["HumanTask<br/>type: APPROVAL<br/>assignee: GROUP_1"]
    G1 -- "outcome = APPROVED" --> G2["HumanTask<br/>type: APPROVAL<br/>assignee: GROUP_2"]
    G1 -- "outcome = REJECTED" --> Rej([REJECTED])
    G2 -- "outcome = APPROVED" --> Appr([APPROVED])
    G2 -- "outcome = REJECTED" --> Rej

    classDef task     fill:#1e3a8a,stroke:#38bdf8,color:#bfdbfe
    classDef terminal fill:#14532d,stroke:#22c55e,color:#bbf7d0
    classDef reject   fill:#7f1d1d,stroke:#ef4444,color:#fecaca
    class G1,G2 task
    class Appr terminal
    class Rej reject
```

#### Task lifecycle

Every async step in either sub-workflow is mediated by the same generic
`HumanTask` state machine — that is exactly what makes the REST surface
type-agnostic.

```mermaid
stateDiagram-v2
    [*] --> PENDING : TaskService.create()
    PENDING --> COMPLETED : POST /complete<br/>(valid outcome)
    PENDING --> CANCELLED : POST /cancel
    PENDING --> PENDING : POST /complete<br/>(invalid outcome,<br/>token or terms)
    COMPLETED --> [*]
    CANCELLED --> [*]
```

#### Happy-path interaction (REST + WebSocket)

```mermaid
sequenceDiagram
    autonumber
    actor U  as Requester (Angular)
    actor G1 as Group 1 (Angular)
    actor G2 as Group 2 (Angular)
    participant REST as Quarkus REST
    participant WF   as ApprovalWorkflow
    participant TS   as TaskService
    participant WS   as WebSocket

    U->>REST: POST /api/requests
    REST->>WF: instance.start()
    WF->>TS: create(CONFIRMATION, REQUESTER)
    TS-->>WS: TASK_CREATED
    WS-->>U: live update

    U->>REST: POST /api/tasks/{id}/complete<br/>{ outcome: CONFIRMED, payload }
    REST->>TS: complete(...)
    TS-->>WF: future.complete()
    WF->>TS: create(APPROVAL, GROUP_1)
    TS-->>WS: TASK_COMPLETED + TASK_CREATED
    WS-->>G1: live update

    G1->>REST: POST /api/tasks/{id}/complete<br/>{ outcome: APPROVED }
    REST->>TS: complete(...)
    TS-->>WF: future.complete()
    WF->>TS: create(APPROVAL, GROUP_2)
    WS-->>G2: live update

    G2->>REST: POST /api/tasks/{id}/complete<br/>{ outcome: APPROVED }
    REST->>TS: complete(...)
    TS-->>WF: future.complete()
    WF-->>REST: WorkflowOutput { APPROVED }
    WS-->>U: STATE_CHANGED (APPROVED)
```

## Stack

| Layer    | Tech                                                              |
| -------- | ----------------------------------------------------------------- |
| Workflow | [Quarkus Flow](https://docs.quarkiverse.io/quarkus-flow/dev/) 0.9.0 (CNCF Serverless Workflow DSL) via `io.quarkiverse.flow:quarkus-flow` |
| Backend  | Quarkus 3.35.x on Java 21 LTS — REST (`quarkus-rest-jackson`) + `quarkus-websockets-next` |
| Frontend | Angular 21 (zoneless, signals, signal inputs/outputs, new control flow) + Tailwind CSS v4 |
| Tests    | JUnit 5 / REST-Assured / Awaitility (backend) — Vitest (frontend) |
| Transport | REST (commands) + WebSocket (live state events to the UI)         |

The workflow is defined in two equivalent ways:
* **Runtime source of truth:** Java DSL in
  [`ApprovalWorkflow.java`](backend/src/main/java/com/example/approval/ApprovalWorkflow.java)
  using `FuncWorkflowBuilder` from the Serverless Workflow Java SDK. The
  workflow is modelled as a single CNCF function task that drives all
  stages — branching on outcome happens inline in Java, with no
  exception-based control flow.
* **Declarative reference:** [`approval.yaml`](backend/src/main/flow/approval.yaml)
  in the standard `src/main/flow` location.

Asynchronous wait tasks block on per-request `CompletableFuture`s held in
`TaskService`; the matching REST endpoints complete those futures so
the workflow resumes.

## Tooling

Tool versions are pinned with [mise](https://mise.jdx.dev) and orchestrated
with [task](https://taskfile.dev):

```bash
# one-time, installs Java 21 LTS, Maven 3.9.x, Node 22, Task into the project shell
mise install

# show all available tasks
task --list
```

`mise.toml` pins the language versions; `Taskfile.yml` exposes the
day-to-day commands.

## Run

```bash
task install     # frontend npm install + warm Maven cache
task dev         # backend (8080) + frontend (4200) in parallel
```

Or run them individually:

```bash
task dev:backend         # quarkus:dev, live reload at http://localhost:8080
task dev:frontend        # ng serve at http://localhost:4200
```

### Without task

```bash
cd backend && mvn quarkus:dev
# in another shell:
cd frontend && npm install && npm start
```

REST surface — **generic, task-based**. Each async step in the workflow
emits a `HumanTask`; clients drive the workflow forward by completing or
cancelling tasks. No new REST routes are needed when stages are added.

| Method | Path                                | Purpose                                                |
| ------ | ----------------------------------- | ------------------------------------------------------ |
| POST   | `/api/requests`                     | Create request, starts workflow instance               |
| GET    | `/api/requests`                     | List all requests (with embedded tasks + history)      |
| GET    | `/api/requests/{id}`                | Get a single request                                   |
| GET    | `/api/tasks?status=&group=&requestId=` | List tasks (with optional filters)                  |
| GET    | `/api/tasks/{id}`                   | Get a single task                                      |
| POST   | `/api/tasks/{id}/complete`          | Complete a task: `{ actor, outcome, payload }`         |
| POST   | `/api/tasks/{id}/cancel`            | Cancel a pending task: `{ actor, reason }`             |
| WS     | `/approval-events`                  | Server-pushed request- and task-lifecycle events       |

Task types currently emitted by `ApprovalWorkflow`:

| Type           | Assignee   | Valid outcomes        | Payload                          |
| -------------- | ---------- | --------------------- | -------------------------------- |
| `CONFIRMATION` | `REQUESTER`| `CONFIRMED`, `CANCELLED` | `{ token, termsAccepted }` |
| `APPROVAL`     | `GROUP_1`, `GROUP_2` | `APPROVED`, `REJECTED` | optional `{ reason }` |

### Frontend

```bash
cd frontend
npm install
npm start               # http://localhost:4200
```

CORS is pre-configured for `http://localhost:4200`.

The UI is built with Angular 21 and leans on the modern stack:

* **Zoneless change detection** (`provideZonelessChangeDetection`) — no
  `zone.js` polyfill, all reactivity flows through signals.
* **Signal-based components** — `signal()`, `computed()`, `effect()`,
  `input.required<T>()`, `output<T>()`. `ChangeDetectionStrategy.OnPush`
  on every component.
* **Lazy-loaded standalone routes** via `loadComponent`.
* **Router component-input binding** (`withComponentInputBinding`) +
  **view transitions** (`withViewTransitions`).
* **New control-flow syntax** in templates — `@if` / `@else if` /
  `@else`, `@for ... track`, `@switch / @case`, `@let`.
* **`inject()` everywhere** instead of constructor parameter
  injection.
* **Tailwind CSS v4** with CSS-first config (`@theme { ... }`) and
  custom component utilities via `@layer components`. PostCSS is wired
  through `@tailwindcss/postcss` in `.postcssrc.json`.

## Try it end-to-end

1. Open <http://localhost:4200>.
2. Fill in the form (requester + email + subject), tick the terms checkbox,
   click **Submit request**.
3. The detail page opens with the request in **Awaiting confirmation**. A
   simulated email is shown with a Confirm button.
4. Tick the terms checkbox in the email panel and click **Confirm**.
   The workflow advances to **Awaiting Group 1**.
5. Enter an approver name and click **Approve**. The flow advances to **Group 2**.
6. Approve again from Group 2 — the request reaches **Approved**.

All transitions are pushed to the UI in real time via WebSocket; the SVG
flow diagram highlights the current state.

## Tests

```bash
task test           # backend + frontend
task test:backend   # Quarkus / JUnit5 / RestAssured
task test:frontend  # Angular / Vitest (jsdom)
task e2e            # curl-based smoke test against a running stack
```

* **Backend** — `ApprovalFlowTest` exercises the full workflow through
  REST: happy path (submit → confirm → group1 approve → group2 approve →
  APPROVED) plus rejection paths at every async step, invalid input,
  wrong token and missing terms acceptance.
* **Frontend** — `models/approval.spec.ts` and `services/approval.spec.ts`
  run on [Vitest](https://vitest.dev) via Angular CLI's
  `@angular/build:unit-test` builder. They cover the state-helper
  functions and the HTTP service contracts using Angular's
  `HttpTestingController`.
* **End-to-end** — `scripts/e2e-smoke.sh` drives the running stack from
  the outside via `curl` and verifies the terminal state.

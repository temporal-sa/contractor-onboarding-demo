# Contractor Onboarding: Temporal demo

Java, Spring Boot, Temporal Java SDK via the Temporal Spring Boot starter. One jar, two processes: an API with a web UI, and a worker you can kill on stage.

This is a demo, not a reference integration. Every external system is simulated: activities log instead of calling
real services, and the contractor registry is a local JSON file.

## Prerequisites

- Java 17 or newer
- Maven 3.9 or newer
- [Temporal CLI](https://docs.temporal.io/cli) for the local dev server (`temporal server start-dev`)
- macOS or Linux. The shell scripts use bash and `kill -9`; on Windows, run them under WSL.

Tested with Java 25, Maven 3.9.16, Temporal CLI 1.9.1 (Server 1.32.0), Temporal Java SDK and Spring Boot starter 1.40.0, and Spring Boot 3.3.5.

## Build

```bash
mvn -q -DskipTests package
```

The run scripts build the jar automatically if `target/` has none.

## Run locally

```bash
temporal server start-dev            # terminal 1 (UI at http://localhost:8233)
./run-worker.sh                      # terminal 2
./run-api.sh                         # terminal 3, then open http://localhost:8080
```

`DEMO_TIMESCALE` defaults to 4320 in the scripts, so one business day passes in about 20 seconds.
Set it identically for the worker and the api (the api passes it into each workflow).
`run-api.sh` sets `SWEEP_START_PAUSED=true`, so the Schedule starts paused (and an existing one is paused on api startup)
until you press Resume or Trigger now. Run `SWEEP_START_PAUSED=false ./run-api.sh` to let it fire on its own.

## Connecting to Temporal

The Temporal Spring Boot starter builds the client, schedule client, and workers from the `spring.temporal`
properties in `application.yml`. Those properties read the same `TEMPORAL_*` variables as the Temporal CLI. With
nothing set, the app connects to `localhost:7233`, namespace `default`. Each process logs the address, namespace, and
auth mode it uses at startup; check that line if a connection setting seems to be ignored.

### Temporal Cloud

Same build, only environment variables change. Set them for both scripts.

```bash
export TEMPORAL_ADDRESS=<region>.<cloud>.api.temporal.io:7233   # API key endpoint, or <ns>.<acct>.tmprl.cloud:7233 for mTLS
export TEMPORAL_NAMESPACE=<namespace>.<account-id>
export TEMPORAL_API_KEY=<api key>                               # or the two mTLS paths below
# export TEMPORAL_TLS_CLIENT_CERT_PATH=/path/client.pem
# export TEMPORAL_TLS_CLIENT_KEY_PATH=/path/client.key
export TEMPORAL_UI_URL=https://cloud.temporal.io
```

An API key turns on TLS automatically. When `TEMPORAL_TLS_CLIENT_CERT_PATH` is set, the run scripts also activate the
`mtls` Spring profile (`application-mtls.yml`), which requires both certificate paths. If you run the jar yourself, add
it to the profiles: `--spring.profiles.active=worker,mtls`.

Keep API keys and certificates out of the repository. Export them in your shell or load them from a secrets manager;
never commit them to a script or config file.

Only the variables listed below are read. Temporal environment configuration files (`temporal.toml`,
`TEMPORAL_PROFILE`) and other `TEMPORAL_*` variables such as `TEMPORAL_TLS` or `TEMPORAL_GRPC_META_*` are not used.
Any other starter setting can be supplied as a `spring.temporal.*` property or its `SPRING_TEMPORAL_*` environment
variable.

## Environment variables

Connection:

| Variable | Default | Purpose |
|---|---|---|
| TEMPORAL_ADDRESS | localhost:7233 | Frontend address |
| TEMPORAL_NAMESPACE | default | Namespace |
| TEMPORAL_API_KEY | empty | API key auth (enables TLS) |
| TEMPORAL_TLS_CLIENT_CERT_PATH, TEMPORAL_TLS_CLIENT_KEY_PATH | empty | mTLS auth (the run scripts then activate the `mtls` profile) |

Demo:

| Variable | Default | Purpose |
|---|---|---|
| TEMPORAL_TASK_QUEUE | contractor-onboarding | Task queue |
| TEMPORAL_UI_URL | http://localhost:8233 | Base URL for UI deep links |
| DEMO_TIMESCALE | 1 (scripts use 4320) | Divides SLAs and the sweep interval |
| SWEEP_INTERVAL | PT24H | Real sweep cadence, divided by the timescale |
| SWEEP_EXPIRY_WINDOW_DAYS | 30 | Look-ahead window for expiring coverage |
| SWEEP_START_PAUSED | false (run-api.sh uses true) | Create the Schedule paused, and pause an existing one at api startup |
| REGISTRY_PATH | ./data/registry.json | Simulated contractor registry |
| ACTIVITY_DELAY_MS | 800 | Artificial latency per activity |

`SWEEP_INTERVAL`, `SWEEP_EXPIRY_WINDOW_DAYS`, and `DEMO_TIMESCALE` are baked into the Schedule when it is created.
After changing them, restart the api and press Reset in the Schedule panel (or `POST /api/schedule/reset`).

## Compliance sweep

The api creates a Temporal Schedule, `contractor-compliance-sweep`, at startup if it does not already exist.

1. Every `SWEEP_INTERVAL` divided by `DEMO_TIMESCALE` (about 20 seconds at demo speed) the Schedule starts a
   `ComplianceSweepWorkflow`. The overlap policy is SKIP, so a slow sweep never stacks runs.
2. The sweep finds every `ACTIVE` contractor whose insurance or license expires within `SWEEP_EXPIRY_WINDOW_DAYS`.
3. For each one it starts a `CoverageRenewalWorkflow` with the id `renewal-<contractor>-<type>-<expiry>` and the
   reject-duplicate reuse policy, so repeated sweeps never start a second renewal for the same expiry. The sweep result
   reports how many it found, how many renewals it started, and how many were already open.
4. A renewal sends a reminder and waits up to its SLA (2 days, about 40 seconds at demo speed) for the
   `renewalSubmitted` signal, which the Renew button sends. Without it, the renewal escalates twice, then suspends
   the contractor (about 2 minutes in total). With it, the new expiry (today plus 365 days) is written to the registry.

## Resetting the demo data

Each run leaves every contractor in the window either renewed (pushed a year out) or suspended. After that the sweep
finds nothing to do. To start over:

```bash
./kill-worker.sh                     # and stop run-api.sh with Ctrl-C
rm data/registry.json
./run-worker.sh
./run-api.sh
```

Stop both processes before deleting the file: the registry is seeded only at startup, when the file is missing.
Seed dates are relative to the day the file is created, so a fresh seed always puts three coverages inside the
30-day window: Apex Plumbing insurance (6 days), BrightSpark Electric license (14 days), and Cool Breeze HVAC
insurance (25 days).

## Demo script

1. Start a HAPPY_PATH onboarding. Watch the parallel license and insurance checks, approve the Banking/AP task when it appears, then sign the contract.
2. Start SLOW_APPROVER. Do nothing. Screening escalates to the lead, then the manager, then the card turns amber.
3. Start SLOW_BANKING. While "Bank/AP" is active, run `./kill-worker.sh`. The card shows last known state. Run `./run-worker.sh` again and it resumes. The heartbeat timeout is 6s, so recovery is quick.
4. Start BAD_LICENSE or LAPSED_INSURANCE for the rejection and correction paths.
5. Schedule panel: it starts paused. Press Trigger now to run one sweep, then Resume to watch it fire on its cadence, then Pause. Renewal workflows start for contractors in the registry with coverage expiring within the window. Press Renew on one within about 2 minutes; leave another to escalate and suspend.

## Layout

```
workflow/   ContractorOnboardingWorkflow (parent, signals, queries, SLA timers, parallel verification)
            ComplianceSweepWorkflow (started by the Schedule)
            CoverageRenewalWorkflow (started per expiring coverage, idempotent workflow id)
activity/   simulated integrations, scenario driven
schedule/   SweepScheduleService (create, describe, trigger, pause, resume, reset)
registry/   file-backed simulated contractor system of record
api/        REST controller consumed by static/index.html
config/     customizers for what the spring.temporal properties cannot set (query timeout, client identity)
```

## Design notes

- Human waits use `Workflow.await(timeout, condition)`: a decision signal or the SLA timer, whichever comes first. First expiry escalates to the team lead, second to the manager and flags the onboarding at risk, third withdraws it.
- License and insurance verification run in parallel with `Async.function`. Both outcomes are recorded before any decision is made.
- Renewal workflows are started from an activity with a deterministic workflow id and the reject-duplicate reuse policy, so repeated sweeps never start duplicates.
- The api caches last-known state per onboarding, so the UI keeps working when the worker is killed (queries need a worker).
- The "worker online" chip uses recent task queue pollers and can lag about a minute. The per-card "worker unreachable" badge reacts in about 3 seconds.
- Review steps support approve and reject. A request-more-info step is not implemented.

## License

MIT. See [LICENSE](LICENSE).

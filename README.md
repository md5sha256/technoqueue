# Technoqueue

A Velocity proxy plugin that holds connecting players in a fallback "limbo"
server when a target server is at capacity, draining the queue back into the
target as slots free up. Connections are rejected when the queue itself is
full.

## Project layout

```
src/main/java/...        Plugin source (Java 21, Velocity 3.5)
src/test/java/...        JUnit 5 tests for QueueManager and PlayerQueue
src/main/resources/      velocity-plugin.json, default technoqueue.yml
docker/proxy/Dockerfile  Multi-stage image: gradle build → itzg/mc-proxy
docker/velocity/         velocity.toml, forwarding.secret, plugin config
docker/limbo-{a,b}.toml  PicoLimbo configs for the two dev backends
compose.yaml             Three-service dev stack (proxy + 2 limbos)
```

## Local dev stack

The compose stack runs a Velocity proxy with the plugin pre-built into the
image, plus three PicoLimbo backends:

| Service          | Role                                       | Host port | Container port |
|------------------|--------------------------------------------|-----------|----------------|
| `proxy`          | Velocity + Technoqueue plugin              | 25565     | 25577          |
| `limbo-target-1` | Mock target server, **queue-managed**      | 25566     | 25565          |
| `limbo-target-2` | Mock target server, **no queue**           | 25567     | 25565          |
| `limbo-queue-1`  | Mock waiting-room (fallback for the queue) | 25568     | 25565          |
| `limbo-queue-2`  | Mock waiting-room (second fallback)        | 25569     | 25565          |

The dev `settings.yml` registers only `limbo-target-1` as queue-managed, with
`limbo-queue-1` as its fallback. `limbo-target-2` is registered with Velocity
but has no entry in `settings.yml`, so connections to it always pass through
directly. `limbo-queue-2` is unused by default — wire it up as the `fallback`
for a second queue entry to exercise multi-queue behavior.

1. Player 1 connects to `localhost:25565` → routed to `limbo-target-1` (via
   Velocity's `try` list).
2. Player 2 connects → `limbo-target-1` is at capacity, player is queued and
   lands on `limbo-queue-1`. Once Player 1 disconnects, the next drain tick
   promotes Player 2 into `limbo-target-1`.
3. From inside the proxy, `/server limbo-target-2` switches directly — no
   queue, no capacity check.

### One-time setup

```powershell
# 1. Set the shared forwarding secret used by Velocity ↔ PicoLimbo.
Copy-Item .env.example .env
# Edit .env and replace FORWARDING_SECRET with a long random string.

# 2. Mirror the secret into the forwarding.secret file Velocity reads.
$secret = (Select-String -Path .env -Pattern '^FORWARDING_SECRET=').Line.Split('=', 2)[1]
Set-Content -NoNewline -Path docker\velocity\forwarding.secret -Value $secret
```

### Bring up / tear down

```powershell
docker compose up --build -d            # builds the plugin jar inside the proxy image, then boots all three services
docker compose logs -f proxy            # follow Velocity logs
docker compose ps                       # status of all services
docker compose down                     # stop everything
docker compose down -v                  # stop + drop the proxy-data volume
```

Connect a Minecraft 1.21.8 client to `localhost:25565`.

### Iterating on the plugin

The proxy image embeds the plugin jar via `docker/proxy/Dockerfile`, which runs
`gradle build -x test` in a `gradle:8.10-jdk21` builder stage and copies the
resulting jar into `/server/plugins/` of the `itzg/mc-proxy` runtime stage. A
BuildKit cache mount (`/home/gradle/.gradle`) keeps Gradle's dependency cache
warm across builds, so subsequent rebuilds finish in seconds.

After editing Java sources:

```powershell
docker compose up -d --build proxy      # rebuild image + restart proxy in one shot
# or
docker compose build proxy              # rebuild image without restarting
docker compose restart proxy            # pick up the new image
```

`velocity.toml` and `technoqueue.yml` are bind-mounted into the proxy
container, so config edits do **not** require an image rebuild:

```powershell
docker compose restart proxy            # reloads both configs
```

### Running tests on the host

The Docker build skips tests with `-x test` to keep iteration fast. Run them on
the host before committing:

```powershell
./gradlew test
```

## Plugin configuration

`technoqueue.yml` lives at `plugins/technoqueue/technoqueue.yml` relative to
the Velocity working directory. In the dev stack it's mounted from
`docker/velocity/plugins/technoqueue/technoqueue.yml`.

```yaml
drain-interval-seconds: 10   # how often each queue tries to promote its head

servers:
  <velocity-server-name>:
    queue-settings:
      target-capacity: 200  # max players on the backend before queueing kicks in
      max-queue-size: 500   # max queued players; further connections are rejected
      fallbacks:            # ordered list of servers where queued players wait
        - <velocity-server-name>

permissions:
  - permission: technoqueue.priority.vip
    weight: 10
  - permission: technoqueue.priority.staff
    weight: 100
```

YAML keys are kebab-case — Configurate's default ObjectMapper derives them from
the camelCase Java field names on `ServerSetting` and `ServerQueueSetting`.

Each entry under `servers` is keyed by a Velocity server name (i.e. a key from
the `[servers]` table in `velocity.toml`).

`permissions` maps a Velocity permission node to a queue weight. When a player
joins a queue, they are inserted ahead of everyone with a strictly lower
weight. Players without any matching permission default to weight 0. Within a
single weight tier, ordering is FIFO.

## Commands

`/queue` is registered on the proxy and exposes the following subcommands:

| Subcommand            | Description                                                       |
|-----------------------|-------------------------------------------------------------------|
| `/queue status`       | Show the running player's position and total size in their queue. |
| `/queue leave`        | Leave the queue the running player is currently in.               |
| `/queue info <server>`| Show the current size of the named server's queue.                |

## Forwarding

The stack uses Velocity's modern player-info forwarding. The same secret must
appear in three places, all sourced from the single `FORWARDING_SECRET` value
in `.env`:

- `docker/velocity/forwarding.secret` — read by Velocity.
- `docker/limbo-a.toml` / `docker/limbo-b.toml` — `secret = "${FORWARDING_SECRET}"`,
  expanded from the env var passed to each PicoLimbo container.
- `.env` — single source of truth, gitignored.

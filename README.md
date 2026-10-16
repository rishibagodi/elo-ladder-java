# Ladder with ELO Ratings

**Language:** Java &nbsp;|&nbsp; **Needs:** Postgres + Redis

This is a **starter**. The application already works. Your job is everything
that gets it building, tested and running in CI.

---

## Submission: what was built (Rishi · `rishibagodi`)

| | |
|---|---|
| **Repository** | https://github.com/rishibagodi/elo-ladder-java |
| **Docker Hub** | https://hub.docker.com/r/rishibagodi/elo-ladder |
| **CircleCI** | https://app.circleci.com/pipelines/github/rishibagodi/elo-ladder-java |
| **K-factor answer** | [The hard part → Our answer](#our-answer-the-k-factor) |

| Area | Where | What |
|---|---|---|
| Git | branches `main`, `staging`, `prod` + `feature/player-crud` | Ruleset on all three (active, no bypass): PR required, no force push, no deletion. Every change arrives by PR; promotions are `main → staging → prod` PRs merged with **merge commits**, so the branches hold identical commits. |
| Code change | `LadderController.java` | `PUT /players/{id}` (rename, keeps Redis in sync), `DELETE /players/{id}` (409 if the player has matches), 409 instead of 500 for duplicate names. `Elo.java` is unchanged. |
| Docker | `Dockerfile`, `.dockerignore` | Multi-stage: Maven build stage, JRE-only runtime. Dependencies cached before the source is copied. Pinned base images, non-root user (uid 10001), `EXPOSE 8080`, no credentials in the image. |
| Compose | `docker-compose.yml` | `db` (Postgres 16.3) and `cache` (Redis 7.2.5) with healthchecks; a one-shot `migrate` service applies `migrations/*.sql` in order; `app` waits for `service_healthy` + `service_completed_successfully`. |
| Unit tests | `src/test/java/com/jain/ladder/EloTest.java` | 16 JUnit 5 tests on the pure ELO maths: both invariants, K-factor boundaries, upset, anti-farming, draw, invalid scores. JaCoCo gate: **≥ 70 % line coverage on `Elo`** (currently 100 %). |
| Integration tests | `src/test/java/com/jain/ladder/LadderIT.java` | 9 `@SpringBootTest` tests over HTTP against real Postgres + Redis: CRUD, 400/404/409 paths, a match updating both Postgres and the Redis sorted set, ladder rebuilt after Redis is wiped. |
| Lint | `pom.xml` (Checkstyle 10.17) | Tabs, line length 120, unused/redundant imports, `equals`/`hashCode`, string `==`, missing switch default and similar. |
| CI | `.circleci/config.yml` | See the table below. |
| Kubernetes | `kube/deployment.yml`, `kube/service.yml`, `kube/pod.yml` | One namespace per environment (`qa`, `stage`, `prod`), each with the app (3 replicas), Postgres and Redis. |

### CI pipeline

| Branch | Jobs | Image tags pushed |
|---|---|---|
| `main` | secret-scan (gitleaks) → lint → unit → integration → build-push | `:qa` and `:main-<short-sha>` |
| `staging` | build-push only | `:stage` and `:staging-<short-sha>` |
| `prod` | build-push only | `:prod` and `:prod-<short-sha>` |
| feature branches | secret-scan → lint → unit → integration (nothing pushed) | none |

The secret scan runs first, so a leaked credential stops the pipeline before anything is built or published. `:qa` / `:stage` / `:prod` are moving tags that each environment pulls. `<branch>-<short-sha>` is immutable and names the exact commit inside an image. Docker Hub credentials are CircleCI project environment variables (`DOCKERHUB_USER`, `DOCKERHUB_TOKEN`, an access token), never files in the repo.

### Run it

```bash
docker compose up --build
curl localhost:8080/health          # {"status":"ok","postgres":true,"redis":true}
```

Swagger UI: http://localhost:8080/swagger-ui.html

### Run the tests

```bash
# unit tests + 70% coverage gate on Elo - no database needed
mvn -B verify -DskipITs

# lint
mvn -B checkstyle:check

# integration tests need Postgres + Redis with migrations applied.
# CircleCI provides them as service containers on localhost.
# Locally, run Maven inside the compose network:
docker compose up -d db cache migrate
docker run --rm --network elo-ladder-java_default -v "$PWD":/app -w /app \
  -e DATABASE_URL=jdbc:postgresql://db:5432/ladder -e DB_USER=ladder -e DB_PASSWORD=ladder \
  -e REDIS_HOST=cache -e REDIS_PORT=6379 \
  maven:3.9.8-eclipse-temurin-17 mvn -B verify -DskipUnitTests=true -Djacoco.skip=true
```

The host is `localhost` in CircleCI but `db` / `cache` in Compose and Kubernetes. The code never hard-codes it: it reads `DATABASE_URL` and `REDIS_HOST` from the environment.

### Deploy to Kubernetes (minikube)

```bash
minikube start --driver=docker
for ns in qa stage prod; do
  kubectl create namespace $ns
  kubectl create secret docker-registry dockerhub --docker-server=https://index.docker.io/v1/ \
    --docker-username=<user> --docker-password=<read-only access token> -n $ns
  kubectl create configmap ladder-migrations --from-file=migrations/ -n $ns
  kubectl apply -f kube/service.yml -n $ns
done
kubectl apply -f kube/deployment.yml -n qa
sed 's/elo-ladder:qa/elo-ladder:stage/' kube/deployment.yml | kubectl apply -n stage -f -
sed 's/elo-ladder:qa/elo-ladder:prod/'  kube/deployment.yml | kubectl apply -n prod  -f -

# after CircleCI pushes a new :qa
kubectl rollout restart deployment/elo-ladder -n qa
kubectl describe pod -l app=elo-ladder -n qa | grep "Image ID"   # matches the Docker Hub digest
```

- `imagePullPolicy: Always` is set because `:qa` is a moving tag.
- The readinessProbe on `/health` keeps a pod out of the Service until it can reach Postgres and Redis.
- Postgres runs the migrations itself from the ConfigMap mounted at `/docker-entrypoint-initdb.d`.
- The Service has no fixed `nodePort`, because node ports are cluster-wide and the same Service exists in all three namespaces.
- `kube/pod.yml` is a bare Pod, kept only to show that, unlike the Deployment, nothing recreates it after deletion.

---

## You do not need Java installed

You will build this into a container, and the container brings its own
Java 17. If you have never written a line of Java, you can still finish
this assignment — you are not being asked to extend the app, you are being
asked to ship it.

Read the two sections below in order. The first tells you what the app needs;
the second is how you turn that into a Dockerfile.

---

## 1. What this app actually needs

This is the whole contract. Everything in your Dockerfile and compose file
comes from this table.

| | |
|---|---|
| **Runtime** | Java 17 |
| **Install dependencies** | `mvn -B dependency:go-offline` |
| **Start the app** | `mvn spring-boot:run` |
| **Listens on** | port 8080, bound to `0.0.0.0` |
| **Environment variables** | `DATABASE_URL` (a JDBC url), `DB_USER`, `DB_PASSWORD`, `REDIS_HOST`, `REDIS_PORT` |
| **Needs running first** | Postgres, Redis, and the migrations applied |

### Endpoints

```
GET  /health                    is it alive, and are Postgres and Redis reachable
GET  /players                   every player, strongest first
POST /players                   {"name": "Anand"}           201, or 409 if the name is taken
PUT  /players/{id}              {"name": "Anand K"}         rename; 404 unknown id, 409 name taken
DELETE /players/{id}                                        204; 409 if the player has matches
GET  /ladder                    the ladder, from a Redis sorted set
POST /matches                   {"playerA": 1, "playerB": 2, "scoreA": 1}
```

`/health` reports Postgres and Redis **separately**. Use it constantly:
if it says `postgres: false`, the app started fine and your compose wiring
is wrong — do not go looking in the application code.

### Migrations

`migrations/` holds `.sql` files that must be applied **in filename order**,
before the app starts. They create the tables and insert sample data, so the
app is worth looking at the first time you run it.

You do not need an ORM or a migration tool. A container running `psql` over
the files in order is enough, and works whatever language the app is in.

---

## 2. What you must write

| File | What it has to do |
|---|---|
| `Dockerfile` | Build this app into an image. Install dependencies **before** copying the source, pin the base image version, and do not run as root. |
| `docker-compose.yml` | Start the app, Postgres, Redis and a migration step with one `docker compose up`. |
| `.circleci/config.yml` | lint → unit tests → integration tests → secret scan → image build |
| Unit tests | For `src/main/java/com/jain/ladder/Elo.java`. No database, no network. |
| Integration tests | Against a real Postgres and Redis as CircleCI service containers. |

Then push your image to **your own Docker Hub account**, tagged with a real
version number — `:1.0`, not only `:latest`.

### When it works

```bash
docker compose up --build
curl localhost:8080/health
```

```json
{"status":"ok","postgres":true,"redis":true}
```

Both true, or you are not finished.

---

## 3. If you want to run it without Docker first

Optional, and only useful if you already have Java 17. Most people should
skip straight to the Dockerfile — the whole point is that the container
removes this step.

```bash
mvn -B dependency:go-offline
export DATABASE_URL=...
mvn spring-boot:run
```

---

## Where the marks are

`src/main/java/com/jain/ladder/Elo.java` is **pure logic** — plain functions over plain data, no database and
no HTTP. That is deliberate, so a 70% coverage target is achievable rather
than busywork. Start your tests there.

Use JUnit. Two properties must always hold and make excellent tests: `expected(a,b) + expected(b,a) == 1.0`, and when both players share a K-factor one player's gain equals the other's loss.

## The hard part

A new player beating a top player should move a lot; a top player beating a new one should barely move. That asymmetry is the K-factor, and getting it wrong makes the ladder gameable — a strong player could farm rating off beginners.

Write your answer to this in your README. It is worth more marks than the
feature itself.

### Our answer: the K-factor

Two things in `Elo.java` together make the ladder fair and impossible to farm.

**1. The expected score removes the reward for predictable wins.**
`expected(a, b) = 1 / (1 + 10^((Rb − Ra) / 400))` and a rating moves by `K × (score − expected)`.
When Anand (2450) plays a 1200 newcomer, his expected score is 0.9993, so winning moves him by `10 × (1 − 0.9993) ≈ 0`.
Ten wins in a row against beginners still earn him **0 points** (tested). A strong player cannot farm rating off weak ones.

**2. The K-factor scales with how certain we are about a rating.**

| Player | Condition | K | Why |
|---|---|---|---|
| Provisional | fewer than 30 games | 40 | the rating is still a guess, so converge quickly |
| Established | 30+ games, rating below 2400 | 20 | the rating is fairly accurate |
| Master | 30+ games, rating 2400 or above | 10 | the top of the ladder should be stable |

So when a newcomer (K 40) upsets Anand (K 10), the newcomer gains **+40** and Anand loses only **−10**.
One surprise moves the uncertain rating a lot and the well-established one a little. A genuinely strong newcomer climbs fast, and one lucky game cannot knock a proven player off the top.

| Seed-data example | Result |
|---|---|
| Newcomer (1200, K 40) beats Anand (2450, K 10) | +40 / −10 |
| Anand beats the newcomer | 0 / 0 |
| Anand beats 10 beginners in a row | 0 total |
| Bhavna (1875) draws Chetan (1640), both K 20 | −6 / +6 |
| Two newcomers (1200 vs 1200) | +20 / −20 |

**Trade-off.** When the two players have different K values, a game is not zero-sum (+40 / −10), so the total rating in the ladder drifts up slightly as new players join. That is the accepted price of letting newcomers reach their real level quickly. When both players share a K, the change is exactly zero-sum.

Every number above is asserted in `EloTest`: both invariants (`expected(a,b) + expected(b,a) = 1` and same-K conservation), the K boundaries at 29/30 games and 2399/2400 rating, the upset, the zero-gain anti-farming case and the draw.

---

## Getting unstuck

| Symptom | Almost always |
|---|---|
| `/health` says `postgres: false` | Wrong hostname. Inside compose the host is the **service name**, not `localhost`. |
| Page will not load, logs look fine | No `ports:` mapping, or the app is bound to `127.0.0.1` instead of `0.0.0.0`. |
| `relation "..." does not exist` | Migrations did not run, or the app started before they finished. |
| Build takes minutes every time | `COPY . .` is above your dependency install. |
| CI cannot reach the database | In CircleCI service containers the host **is** `localhost` — the opposite of compose. |
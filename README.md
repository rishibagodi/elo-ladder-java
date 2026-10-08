# Ladder with ELO Ratings

**Language:** Java &nbsp;|&nbsp; **Needs:** Postgres + Redis

This is a **starter**. The application already works. Your job is everything
that gets it building, tested and running in CI.

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
POST /players                   {"name": "Anand"}
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

---

## Getting unstuck

| Symptom | Almost always |
|---|---|
| `/health` says `postgres: false` | Wrong hostname. Inside compose the host is the **service name**, not `localhost`. |
| Page will not load, logs look fine | No `ports:` mapping, or the app is bound to `127.0.0.1` instead of `0.0.0.0`. |
| `relation "..." does not exist` | Migrations did not run, or the app started before they finished. |
| Build takes minutes every time | `COPY . .` is above your dependency install. |
| CI cannot reach the database | In CircleCI service containers the host **is** `localhost` — the opposite of compose. |

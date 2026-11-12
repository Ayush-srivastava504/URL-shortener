# URL Shortener

A URL-shortening REST API built with **Java 17 and Spring Boot**. It stores links in **PostgreSQL**, uses **Redis** for caching, rate limiting and click counting, authenticates with **JWT**, and is tested with **JUnit 5, Mockito and Testcontainers**.

![CI](https://github.com/Ayush-srivastava504/URL-shortener/actions/workflows/ci.yml/badge.svg)

## Features

- Short links with random 7-character **Base62** codes, or a **custom alias** you choose
- Optional **link expiry**: expired links return `410 Gone`
- **Click counts** for each link
- **JWT authentication** (register/login); each user manages only their own links
- **Redis caching** of redirects, so a cached redirect doesn't query PostgreSQL
- **Rate limiting** with a sliding window in Redis: per user for creating links, per IP for login/register
- Errors returned as **RFC 9457 problem details** (`application/problem+json`)
- Database schema managed by **Flyway** migrations
- **Docker Compose** setup and a **GitHub Actions** CI pipeline that publishes a JaCoCo coverage report

## Architecture

```
                 ┌──────────────────────────── Spring Boot app ────────────────────────────┐
  GET /{code} ──▶│ RedirectController ─▶ UrlService.resolve                                  │
                 │                         ├─ 1. UrlCache (Redis GET url:{code})  ── hit ──▶ 302
                 │                         ├─ 2. miss ─▶ PostgreSQL (unique index on code) ─▶ cache
                 │                         └─ 3. ClickCounter: HINCRBY clicks:pending        │
                 │                                                                          │
  /api/** ──────▶│ JWT filter ─▶ RateLimitFilter (Redis Lua script) ─▶ Url/Auth controllers  │
                 │                                                                          │
                 │ @Scheduled every 10s: RENAME clicks:pending ─▶ batch UPDATE in PostgreSQL │
                 └──────────────────────────────────────────────────────────────────────────┘
```

### Design decisions

| Decision | Why |
|---|---|
| **Random Base62 codes**, not encoded DB ids | Links can't be found by counting up through ids. 62⁷ ≈ 3.5 trillion possible codes, and a collision just means retrying. |
| **Cache-aside in Redis**, TTL capped at the link's expiry | Most traffic is redirects, and many hit the same links, so cached redirects skip the database. A cached entry can't outlive its link. |
| **Clicks counted in Redis, written to the DB in batches** | Redirects never write to PostgreSQL. A `RENAME` of the pending hash swaps it out atomically, so clicks that arrive during a flush go into a fresh hash and aren't lost. |
| **Sliding-window-log rate limiter in a Lua script** | It's exact (no bursts at fixed window edges) and atomic across app instances. If Redis is down, requests are allowed rather than the whole API failing. |
| **302 with `Cache-Control: no-store`** instead of 301 | Browsers would cache a 301 forever, so later clicks would never reach the server or be counted. |
| **404 for other users' links** | Returning 403 would reveal that the link exists. |
| **Redis failures degrade gracefully** | A cache failure becomes a DB lookup, and a click is written straight to the DB. |

## API

| Method | Path | Auth | Description |
|---|---|---|---|
| `POST` | `/api/auth/register` | – | Create an account, returns a JWT |
| `POST` | `/api/auth/login` | – | Log in, returns a JWT |
| `POST` | `/api/urls` | JWT | Create a short link `{url, alias?, expiresInDays?}` |
| `GET` | `/api/urls?page=0&size=20` | JWT | List your links, newest first |
| `GET` | `/api/urls/{code}` | JWT | Link details and click count |
| `DELETE` | `/api/urls/{code}` | JWT | Delete a link |
| `GET` | `/{code}` | – | Redirect (302) |
| `GET` | `/actuator/health` | – | Health check |

### Example

```bash
TOKEN=$(curl -s -X POST localhost:8080/api/auth/register \
  -H 'Content-Type: application/json' \
  -d '{"username":"ayush","password":"s3cure-password"}' | jq -r .accessToken)

curl -s -X POST localhost:8080/api/urls \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"url":"https://github.com/Ayush-srivastava504","alias":"my-github","expiresInDays":30}'

curl -i localhost:8080/my-github      # HTTP/1.1 302, Location: https://github.com/Ayush-srivastava504
```

## Running locally

**With Docker (easiest):**

```bash
docker compose up --build
```

**From your IDE:** start only PostgreSQL and Redis with `docker compose up postgres redis`, then run `./mvnw spring-boot:run`.

**Without setting anything up:** `./mvnw spring-boot:test-run` starts the app with throwaway Testcontainers databases.

## Tests

```bash
./mvnw verify          # unit + integration tests, coverage report in target/site/jacoco/index.html
```

- **Unit tests** (JUnit 5 + Mockito): Base62 encoding, code generation and retries, alias rules, URL validation, cache hit/miss/expiry paths, ownership checks.
- **Integration tests** (Testcontainers with real PostgreSQL and Redis): the full register → create → redirect → click count → delete flow, auth failures, pagination, and rate limiting. They're skipped when Docker isn't running.

## Load testing

```bash
k6 run -e CODE=my-github loadtest/redirect.js           # 1000 req/s for 60s
k6 run -e CODE=my-github -e RATE=3000 loadtest/redirect.js
```

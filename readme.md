# FlowGuard

A production-grade, multi-tenant API Gateway built from scratch in Java + Spring Boot — rate limiting, circuit breaking, JWT auth, IP filtering, async webhook delivery, and full observability via Prometheus + Grafana.

Built to actually understand what a gateway does under the hood, not just wire up a library.

## Features

- **Multi-tenant onboarding** — signup, API key resolution, Redis-cached lookups
- **4 rate-limiting algorithms** — Fixed Window, Token Bucket, Leaky Bucket, Sliding Window — tenant-configurable, atomic via Redis + Lua scripts
- **Circuit breaker** — Redis-backed 3-state FSM (Closed → Open → Half-Open) per tenant/backend pair
- **JWT authentication** — HS256 access tokens, opaque Redis-backed refresh tokens, single-session enforcement
- **IP allow/block rules** — CIDR support, Redis-cached with write-through invalidation
- **Async webhook delivery** — RabbitMQ-backed pipeline, HMAC-SHA256 signed payloads, exponential backoff retries, dead-letter queue for permanently failed deliveries
- **Request logging** — async, non-blocking, tracks rate-limit and IP-block outcomes per request
- **Observability** — Prometheus metrics + pre-built Grafana dashboard (rate-limit decisions, circuit breaker state, webhook outcomes, upstream status classes, JVM/HTTP latency)

## Tech Stack

Java 21 · Spring Boot 3.3 · Spring Security · PostgreSQL · Redis (+ Lua) · RabbitMQ · JWT (jjwt) · Micrometer + Prometheus · Grafana

## Getting Started

### Prerequisites

- Java 21+
- Maven
- PostgreSQL running locally (or update the connection URL)
- Redis running locally
- RabbitMQ running locally
- Docker (optional, for the Prometheus/Grafana stack)

### Setup

1. Clone the repo
   ```
   git clone https://github.com/va1bhava/FlowGuard.git
   cd FlowGuard
   ```

2. Copy the config template and fill in your own credentials
   ```
   cp src/main/resources/application.yml.example src/main/resources/application.yml
   ```
   Edit `application.yml` with your Postgres password, JWT secret, and any other local values. This file is gitignored — it will never be committed.

3. Run the app
   ```
   mvn spring-boot:run
   ```
   The gateway starts on `http://localhost:8080`.

### Monitoring (optional)

Spin up Prometheus + Grafana alongside the running app:
```
docker compose -f docker-compose.monitoring.yml up -d
```
- Prometheus: `http://localhost:9090`
- Grafana: `http://localhost:3000` (login `admin` / `admin`) — the FlowGuard dashboard is pre-provisioned.

## API Overview

| Area | Endpoint | Auth |
|---|---|---|
| Tenant signup | `POST /api/tenants/signup` | Open |
| Login | `POST /api/tenants/auth/login` | Open |
| Refresh token | `POST /api/tenants/auth/refresh` | Open |
| Logout | `POST /api/tenants/auth/logout` | Open |
| Webhooks | `POST/GET /api/tenants/{tenantId}/webhooks`, `DELETE /api/tenants/{tenantId}/webhooks/{webhookId}` | JWT |
| IP rules | `POST/GET /api/tenants/{tenantId}/ip-rules`, `DELETE /api/tenants/{tenantId}/ip-rules/{ruleId}` | JWT |
| Request logs | `GET /api/tenants/{tenantId}/logs` | JWT |
| Health | `GET /ping`, `GET /health` | Open |
| Metrics | `GET /actuator/prometheus` | Open |

All other paths are treated as proxied tenant traffic — authenticated via `X-API-Key`, gated by rate limiting, IP rules, and circuit breaking before reaching the configured backend.

## Design Notes

- **No tenant ID in Prometheus labels** — avoids unbounded cardinality; metrics are tagged by algorithm/outcome/event type instead.
- **Webhook delivery never blocks the request path** — dispatched via `@Async` + RabbitMQ, so a slow or dead customer endpoint can't slow down FlowGuard itself.
- **Circuit breaker state transitions are atomic** — enforced via Lua scripts executed directly in Redis, avoiding race conditions between concurrent requests.

## Roadmap

- [ ] Dockerize the full application stack
- [ ] Integration tests
- [ ] Deployment (Render/Railway)

## Author

D Yoga Venkata Prasad — [github.com/va1bhava](https://github.com/va1bhava)
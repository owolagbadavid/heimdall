# Heimdall

A reactive, distributed rate-limiting service built with Spring Boot 4 / WebFlux. Heimdall enforces per-caller token-bucket limits via an atomic Lua script on Redis and exposes its API over both **HTTP (REST)** and **gRPC**.

## Table of Contents

- [How It Works](#how-it-works)
- [Architecture](#architecture)
- [Prerequisites](#prerequisites)
- [Configuration](#configuration)
- [Running Locally](#running-locally)
- [Docker](#docker)
- [REST API](#rest-api)
- [gRPC API](#grpc-api)
- [Redis Sharding](#redis-sharding)
- [Tech Stack](#tech-stack)

---

## How It Works

1. **Rules** define rate-limit policies. Each rule maps an `(api, op)` pair to a maximum token count and a refill window (in seconds).
2. When a caller hits `tryConsume(api, op, key)`, Heimdall:
   - Looks up the matching rule (Redis cache → PostgreSQL fallback).
   - Runs an atomic Lua script on Redis that refills the caller's token bucket proportionally to elapsed time and then tries to consume one token.
   - Returns the remaining tokens (`≥ 0`), `-1` if rate-limited, or `-2` if no rule exists.
3. Rules are cached in Redis and refreshed from the database every 60 seconds by a background worker, so policy changes propagate without a restart.

### Token-Bucket Algorithm

Each bucket is a Redis Hash with two fields:

| Field          | Description                                          |
|----------------|------------------------------------------------------|
| `tokens`       | Current token count (fractional double)              |
| `last_request` | Epoch timestamp in seconds (microsecond precision)   |

On every consume call the Lua script:
1. Reads `tokens` and `last_request` from the hash.
2. If the bucket is new, initialises it with `max_tokens − 1` and returns.
3. Otherwise, refills `tokens += elapsed × (max_tokens / window_seconds)`, capped at `max_tokens`.
4. Consumes one token if available; returns `-1` otherwise.

All of this runs in a single Redis round-trip, so there are no race conditions.

---

## Architecture

```
┌─────────────────────────────────────────────┐
│                  Heimdall                   │
│                                             │
│  HTTP :8080          gRPC :9090             │
│  ┌────────────┐      ┌──────────────────┐   │
│  │RuleController│    │RateLimitGrpc     │   │
│  │TokenController│   │Service           │   │
│  └─────┬──────┘      └────────┬─────────┘   │
│        │                      │             │
│  ┌─────▼──────────────────────▼─────────┐   │
│  │          RuleService / TokenService  │   │
│  └─────┬──────────────────────┬─────────┘   │
│        │                      │             │
│  ┌─────▼──────┐      ┌────────▼──────────┐  │
│  │RuleRepository│    │TokenRepository    │  │
│  │(PostgreSQL) │    │(Redis Lua scripts) │  │
│  └─────────────┘    └────────┬───────────┘  │
│                              │              │
│                     ┌────────▼───────────┐  │
│                     │   RedisClient      │  │
│                     │ (consistent-hash   │  │
│                     │  shard routing)    │  │
│                     └────────────────────┘  │
└─────────────────────────────────────────────┘
```

**Rule lookup flow:** Redis cache → PostgreSQL (write-through on miss)  
**Rule cache refresh:** Background worker polls every 60 s (configurable)

---

## Prerequisites

| Requirement | Version |
|-------------|---------|
| Java        | 25+     |
| Maven       | 3.9+    |
| PostgreSQL  | 13+     |
| Redis       | 6+      |

---

## Configuration

Copy `sample.env` and fill in your values:

```bash
cp sample.env .env
```

| Variable           | Default       | Description                              |
|--------------------|---------------|------------------------------------------|
| `DB_URL`           | —             | R2DBC PostgreSQL URL (`r2dbc:postgresql://host:5432/db`) |
| `DB_USERNAME`      | —             | PostgreSQL username                      |
| `DB_PASSWORD`      | —             | PostgreSQL password                      |
| `REDIS_HOST`       | `localhost`   | Redis hostname                           |
| `REDIS_PORT`       | `6379`        | Redis port                               |
| `REDIS_PASSWORD`   | —             | Redis password (leave unset if none)     |
| `REDIS_USERNAME`   | —             | Redis username (leave unset if none)     |
| `GRPC_PORT`        | `9090`        | gRPC server port                         |
| `GRPC_REFLECTION`  | `true`        | Enable gRPC server reflection            |
| `SQL_INIT_MODE`    | `always`      | Whether to run `schema.sql` on startup   |

### Optional: Cache tuning

```properties
heimdall.cache.refresh-interval-ms=60000   # rule cache refresh interval
heimdall.cache.refresh-batch-size=100       # rules per batch during refresh
```

---

## Running Locally

```bash
# 1. Export environment variables
export $(cat .env | xargs)

# 2. Build and run
./mvnw spring-boot:run
```

The application starts on:
- HTTP: `http://localhost:8080`
- gRPC: `localhost:9090`
- Swagger UI: `http://localhost:8080/swagger-ui.html`

---

## Docker

### Build the image

```bash
docker build -t heimdall-app:latest .
```

### Run with Docker Compose

```bash
# Set environment variables first
export DB_URL=r2dbc:postgresql://host.docker.internal:5432/heimdall
export DB_USERNAME=postgres
export DB_PASSWORD=yourpassword

docker compose up
```

The compose file maps:
- `8080` → HTTP
- `9090` → gRPC

---

## REST API

Full interactive docs at `/swagger-ui.html`.

### Rules

Rules define rate-limit policies. Each rule is uniquely identified by its `(api, op)` pair.

| Method | Path          | Description                          |
|--------|---------------|--------------------------------------|
| `POST` | `/rules`      | Create a rule                        |
| `GET`  | `/rules`      | List rules (token-paginated)         |
| `GET`  | `/rules/{id}` | Get a rule by ID                     |
| `PUT`  | `/rules/{id}` | Update a rule                        |
| `DELETE` | `/rules/{id}` | Delete a rule                      |

**Create rule — request body:**

```json
{
  "name": "Payment API create-order limit",
  "api": "payment-api",
  "op": "createOrder",
  "timeInSeconds": 60,
  "rateLimit": 100
}
```

**List rules — query params:**

| Param       | Default | Description                              |
|-------------|---------|------------------------------------------|
| `limit`     | `20`    | Page size (1–100)                        |
| `nextToken` | —       | Cursor from previous response            |

**List rules — response:**

```json
{
  "items": [ { "id": "...", "api": "payment-api", "op": "createOrder", ... } ],
  "nextToken": "abc123"
}
```

---

### Token Buckets (Rate Limiter)

| Method   | Path                       | Description                                    |
|----------|----------------------------|------------------------------------------------|
| `POST`   | `/api/v1/tokens/consume`   | Try to consume one token                       |
| `GET`    | `/api/v1/tokens/remaining` | Peek at remaining tokens (no consume)          |
| `DELETE` | `/api/v1/tokens/reset`     | Reset a bucket (refills on next request)       |

All three endpoints share the same query parameters:

| Param | Description                              | Example       |
|-------|------------------------------------------|---------------|
| `api` | API identifier                           | `payment-api` |
| `op`  | Operation identifier                     | `createOrder` |
| `key` | Caller identifier (user ID, IP, etc.)    | `user-42`     |

**Consume response:**

```json
{
  "allowed": true,
  "remaining": 99,
  "api": "payment-api",
  "op": "createOrder",
  "key": "user-42"
}
```

| `remaining` value | Meaning                   |
|-------------------|---------------------------|
| `≥ 0`             | Allowed — tokens left     |
| `-1`              | Denied — rate limited     |
| `-2`              | Denied — no rule found    |

---

## gRPC API

Proto definition: [src/main/proto/rate_limit_service.proto](src/main/proto/rate_limit_service.proto)

```protobuf
service RateLimitService {
  rpc CheckRateLimit(RateLimitRequest) returns (RateLimitResponse);
}

message RateLimitRequest {
  string api = 1;
  string op  = 2;
  string key = 3;  // user ID, IP address, API key, etc.
}

message RateLimitResponse {
  bool  allowed          = 1;
  int64 remaining_tokens = 2;
}
```

Server reflection is enabled by default (`GRPC_REFLECTION=true`), so tools like `grpcurl` work without supplying the proto:

```bash
grpcurl -plaintext \
  -d '{"api":"payment-api","op":"createOrder","key":"user-42"}' \
  localhost:9090 \
  dev.tobee.heimdall.services.grpc.RateLimitService/CheckRateLimit
```

---

## Redis Sharding

Heimdall supports distributing token buckets and rule caches across multiple Redis nodes using a **consistent hash ring**. When sharding is disabled, the standard `spring.data.redis.*` single-node connection is used.

### Enable sharding

In `application.properties` (or via environment / config server):

```properties
heimdall.redis.virtual-nodes=150          # virtual nodes per shard (default: 150)
heimdall.redis.shards[0].host=redis-1
heimdall.redis.shards[0].port=6379
heimdall.redis.shards[1].host=redis-2
heimdall.redis.shards[1].port=6379
```

Each key is deterministically routed to the shard whose MD5-based ring position is closest (clockwise) to the key's own hash. Adding or removing shards only remaps a fraction of the keyspace.

---

## Tech Stack

| Layer        | Technology                                    |
|--------------|-----------------------------------------------|
| Runtime      | Java 25, Spring Boot 4.0                      |
| HTTP         | Spring WebFlux (Netty)                        |
| gRPC         | Spring gRPC 1.0, gRPC Java 1.77, Protobuf 4  |
| Database     | PostgreSQL via R2DBC (fully reactive)         |
| Cache        | Redis (Lua scripts for atomic token-bucket)   |
| API Docs     | SpringDoc OpenAPI 3 (Swagger UI)              |
| Build        | Maven, protobuf-maven-plugin                  |
| Container    | Docker (multi-stage, Eclipse Temurin 25)      |

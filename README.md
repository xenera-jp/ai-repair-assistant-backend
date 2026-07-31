# AI Repair Assistant Backend

Spring Boot backend and RAG-Core for the AI repair assistant.

This V1 implements the complete pre-departure diagnosis path:

1. Import the fixed Excel knowledge pack.
2. Build structured maintenance cases in MySQL.
3. Generate 512-dimensional OpenAI embeddings and index them in Qdrant.
4. Interpret a natural-language maintenance problem.
5. Prefer deterministic SQL retrieval, then use vector retrieval as fallback.
6. Return up to three causes with evidence, parts, tools and repair steps.

## Stack

- Java 21
- Spring Boot 4.1
- MySQL 8.4
- Qdrant 1.18
- OpenAI
- Flyway

## Local start

Place `OPENAI_API_KEY` in `.env.local` in this repository or its parent
directory. The key is never committed.

```bash
cp .env.example .env.local
docker compose up -d
JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home ./mvnw spring-boot:run
```

On the first start, Flyway creates the MySQL schema and the importer loads the
three workbooks under `data/knowledge`. Repeated starts are idempotent.

Backend:

```text
http://localhost:8080
GET /api/v1/system/status
GET /actuator/health
POST /api/v1/problem-understandings
POST /api/v1/diagnosis-sessions
```

Default local infrastructure:

- MySQL: `localhost:3307`
- Qdrant REST: `localhost:6333`
- Qdrant gRPC: `localhost:6334`

Demo input:

```text
RIR1-SSB 冷却效果明显下降，背面发热，显示 E4。设备仍在运行，但柜内温度持续升高。
```

## Repository ownership

- This repository owns domain rules, database migrations, knowledge construction,
  retrieval planning, OpenAI/Qdrant adapters and the OpenAPI contract.
- API changes start in `docs/api/openapi.yaml`.
- The frontend repository consumes the contract and must not duplicate diagnosis rules.

See [the collaboration workstreams](docs/collaboration/WORKSTREAMS.md).

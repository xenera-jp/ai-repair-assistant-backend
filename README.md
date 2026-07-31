# AI Repair Assistant Backend

Spring Boot backend and RAG-Core for the AI repair assistant.

## Stack

- Java 21
- Spring Boot 4.1
- MySQL 8.4
- Qdrant 1.18
- OpenAI
- Flyway

## Local start

```bash
cp .env.example .env
docker compose up -d
set -a
source .env
set +a
JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home ./mvnw spring-boot:run
```

Backend:

```text
http://localhost:8080
GET /api/v1/system/status
GET /actuator/health
```

## Repository ownership

- This repository owns domain rules, database migrations, knowledge construction,
  retrieval planning, OpenAI/Qdrant adapters and the OpenAPI contract.
- API changes start in `docs/api/openapi.yaml`.
- The frontend repository consumes the contract and must not duplicate diagnosis rules.

See [the collaboration workstreams](docs/collaboration/WORKSTREAMS.md).

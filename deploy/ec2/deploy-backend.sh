#!/usr/bin/env bash

# Deploys a backend image on the EC2 self-hosted runner. The OpenAI key remains
# in DEPLOY_ROOT/.env.local and is never copied into the repository or image.
set -Eeuo pipefail

CONTAINER_NAME="${CONTAINER_NAME:-ai-repair-backend}"
ROLLBACK_NAME="${CONTAINER_NAME}-rollback"
IMAGE_NAME="${IMAGE_NAME:-ai-repair-assistant-backend}"
DEPLOY_ROOT="${DEPLOY_ROOT:-/home/ec2-user/app/ai-repair-assistant-backend}"
REPOSITORY_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
ENV_FILE="${APP_ENV_FILE:-${DEPLOY_ROOT}/.env.local}"
COMPOSE_FILE="${DEPLOY_ROOT}/compose.yaml"
HEALTH_URL="${HEALTH_URL:-http://127.0.0.1:8080/actuator/health}"
STATUS_URL="${STATUS_URL:-http://127.0.0.1:8080/api/v1/system/status}"

if [[ ! -f "${ENV_FILE}" ]]; then
  echo "Required secret environment file is missing: ${ENV_FILE}" >&2
  exit 1
fi

if [[ ! -f "${COMPOSE_FILE}" ]]; then
  echo "Required infrastructure Compose file is missing: ${COMPOSE_FILE}" >&2
  exit 1
fi

echo "Building backend candidate image..."
docker build --pull -t "${IMAGE_NAME}:candidate" "${REPOSITORY_ROOT}"

# MySQL and Qdrant own named volumes in the persistent deployment directory.
# `up` is idempotent and does not recreate healthy services unnecessarily.
docker compose -f "${COMPOSE_FILE}" up -d mysql qdrant

MYSQL_CONTAINER_ID="$(docker compose -f "${COMPOSE_FILE}" ps -q mysql)"
QDRANT_CONTAINER_ID="$(docker compose -f "${COMPOSE_FILE}" ps -q qdrant)"

# The persistent EC2 compose.yaml may predate the restart policy now committed
# to the repository. Updating the live containers makes the deployment
# self-healing immediately without replacing their data-bearing volumes.
docker update --restart unless-stopped \
  "${MYSQL_CONTAINER_ID}" \
  "${QDRANT_CONTAINER_ID}" >/dev/null

NETWORK_NAME="$(docker inspect "${MYSQL_CONTAINER_ID}" \
  --format '{{range $name, $_ := .NetworkSettings.Networks}}{{$name}}{{end}}')"

if [[ -z "${NETWORK_NAME}" ]]; then
  echo "Could not resolve the Docker network used by MySQL." >&2
  exit 1
fi

mysql_ready=false
for attempt in $(seq 1 60); do
  if [[ "$(docker inspect -f '{{if .State.Health}}{{.State.Health.Status}}{{end}}' \
    "${MYSQL_CONTAINER_ID}" 2>/dev/null)" == "healthy" ]]; then
    mysql_ready=true
    break
  fi

  echo "Waiting for MySQL health (${attempt}/60)..."
  sleep 2
done

if [[ "${mysql_ready}" != true ]]; then
  echo "MySQL did not become healthy in time." >&2
  docker compose -f "${COMPOSE_FILE}" logs --tail 120 mysql >&2 || true
  exit 1
fi

start_backend() {
  local image="$1"
  docker run -d \
    --name "${CONTAINER_NAME}" \
    --restart unless-stopped \
    --network "${NETWORK_NAME}" \
    -p 8080:8080 \
    --env-file "${ENV_FILE}" \
    -e 'MYSQL_URL=jdbc:mysql://mysql:3306/ai_repair_assistant?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai' \
    -e MYSQL_USER=repair_assistant \
    -e MYSQL_PASSWORD=repair_assistant \
    -e QDRANT_URL=http://qdrant:6333 \
    -e QDRANT_COLLECTION=ai_repair_knowledge_v1 \
    -e KNOWLEDGE_SOURCE_PATH=/app/data/knowledge \
    -e KNOWLEDGE_IMPORT_ENABLED=true \
    -e FRONTEND_ORIGIN=http://13.193.148.45 \
    "${image}"
}

restore_previous_backend() {
  echo "The new backend did not become healthy. Restoring the previous container." >&2
  docker logs --tail 160 "${CONTAINER_NAME}" 2>/dev/null || true
  docker rm -f "${CONTAINER_NAME}" >/dev/null 2>&1 || true

  if docker inspect "${ROLLBACK_NAME}" >/dev/null 2>&1; then
    docker rename "${ROLLBACK_NAME}" "${CONTAINER_NAME}"
    docker start "${CONTAINER_NAME}"
  fi

  exit 1
}

# Retain the complete previous container configuration until the candidate has
# passed both the Spring health endpoint and the application status endpoint.
docker rm -f "${ROLLBACK_NAME}" >/dev/null 2>&1 || true
if docker inspect "${CONTAINER_NAME}" >/dev/null 2>&1; then
  docker stop "${CONTAINER_NAME}" >/dev/null
  docker rename "${CONTAINER_NAME}" "${ROLLBACK_NAME}"
fi

if ! start_backend "${IMAGE_NAME}:candidate"; then
  restore_previous_backend
fi

healthy=false
for attempt in $(seq 1 150); do
  if curl --fail --silent --show-error "${HEALTH_URL}" >/dev/null \
    && curl --fail --silent --show-error "${STATUS_URL}" >/dev/null; then
    healthy=true
    break
  fi

  if ! docker inspect -f '{{.State.Running}}' "${CONTAINER_NAME}" 2>/dev/null \
    | grep -q true; then
    break
  fi

  echo "Waiting for backend health (${attempt}/150)..."
  sleep 2
done

if [[ "${healthy}" != true ]]; then
  restore_previous_backend
fi

docker rm -f "${ROLLBACK_NAME}" >/dev/null 2>&1 || true
docker tag "${IMAGE_NAME}:candidate" "${IMAGE_NAME}:ec2"
docker image prune -f >/dev/null

echo "Backend deployment completed successfully."
curl --fail --silent --show-error "${STATUS_URL}"
echo

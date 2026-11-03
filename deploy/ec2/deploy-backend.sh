#!/usr/bin/env bash
# Replace only the existing backend. Infrastructure and persistent data stay put.
set -Eeuo pipefail
umask 077
CONTAINER_NAME="${CONTAINER_NAME:-ai-repair-backend}"
ROLLBACK_NAME="${CONTAINER_NAME}-rollback"
IMAGE_NAME="${IMAGE_NAME:-ai-repair-assistant-backend}"
DEPLOY_ROOT="${DEPLOY_ROOT:-/home/ec2-user/app/ai-repair-assistant-backend}"
REPOSITORY_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
ENV_FILE="${APP_ENV_FILE:-${DEPLOY_ROOT}/.env.local}"
COMPOSE_FILE="${DEPLOY_ROOT}/compose.yaml"
HEALTH_URL="${HEALTH_URL:-http://127.0.0.1:8080/actuator/health}"
STATUS_URL="${STATUS_URL:-http://127.0.0.1:8080/api/v1/system/status}"
fail() { echo "$*" >&2; exit 1; }
[[ -f "$ENV_FILE" && -f "$COMPOSE_FILE" ]] || fail 'Deployment configuration is missing.'
# Serialize manual runs as well as workflow runs.
exec 9>"${DEPLOY_ROOT}/.backend-deploy.lock"
flock -n 9 || fail 'Another backend deployment is running.'
docker inspect "$ROLLBACK_NAME" >/dev/null 2>&1 && fail 'A retained rollback container exists; finish acceptance or rollback first.'
[[ "$(docker inspect -f '{{.State.Running}}' "$CONTAINER_NAME")" == true ]] || fail 'Expected an existing running backend; this script does not bootstrap infrastructure.'

# Read only the recording mount metadata. Never create a missing data directory.
mount="$(docker inspect -f '{{range .Mounts}}{{if eq .Destination "/app/data/recordings"}}{{.Type}}|{{.Source}}|{{.RW}}{{end}}{{end}}' "$CONTAINER_NAME")"
IFS='|' read -r mount_type existing_recordings mount_rw <<< "$mount"
[[ "$mount_type" == bind && "$mount_rw" == true && -d "$existing_recordings" ]] || fail 'Existing writable recording bind mount is missing or invalid.'
RECORDING_HOST_PATH="${RECORDING_HOST_PATH:-$existing_recordings}"
[[ "$(readlink -f -- "$RECORDING_HOST_PATH")" == "$(readlink -f -- "$existing_recordings")" ]] || fail 'Recording source differs from the existing container.'
# Refuse to silently drop other persistent mounts during replacement.
other_mounts="$(docker inspect -f '{{range .Mounts}}{{if ne .Destination "/app/data/recordings"}}{{println .Destination}}{{end}}{{end}}' "$CONTAINER_NAME")"
[[ -z "$other_mounts" ]] || fail 'Additional backend mounts require an explicit migration plan.'
# Preserve ownership and permissions; do not chmod/chown the historical directory.

mapfile -t networks < <(docker inspect -f '{{range $name, $_ := .NetworkSettings.Networks}}{{println $name}}{{end}}' "$CONTAINER_NAME" | sed '/^$/d')
[[ "${#networks[@]}" -gt 0 ]] || fail 'Existing backend has no Docker network.'
for network in "${networks[@]}"; do
  [[ "$network" != application-gateway && "$network" != host && "$network" != none && "$network" != bridge ]] || fail 'Expected private user-defined backend networks.'
done

# Compose is queried only; no up/down/update/restart of MySQL or Qdrant.
for service in mysql qdrant; do
  id="$(docker compose -f "$COMPOSE_FILE" ps -q "$service")"
  [[ -n "$id" && "$id" != *$'\n'* ]] || fail "Expected one existing $service container."
  [[ "$(docker inspect -f '{{.State.Running}}' "$id")" == true ]] || fail "$service is not running."
  health="$(docker inspect -f '{{if .State.Health}}{{.State.Health.Status}}{{else}}none{{end}}' "$id")"
  [[ "$health" == healthy || "$health" == none ]] || fail "$service is not healthy."
  shared=false
  service_networks="$(docker inspect -f '{{range $name, $_ := .NetworkSettings.Networks}}{{println $name}}{{end}}' "$id")"
  for network in "${networks[@]}"; do
    if grep -Fxq -- "$network" <<< "$service_networks"; then shared=true; fi
  done
  [[ "$shared" == true ]] || fail "$service has no network in common with the backend."
done

# Carry forward public/runtime settings formerly injected by docker run.
# The managed env file is applied second, so intentional configuration updates win.
# Do not print environment values or save them in the repository.
baseline="$(mktemp)"
trap 'rm -f -- "$baseline"' EXIT
docker inspect -f '{{range .Config.Env}}{{println .}}{{end}}' "$CONTAINER_NAME" | \
  awk '/^(MYSQL_URL|MYSQL_USER|MYSQL_PASSWORD|QDRANT_URL|QDRANT_COLLECTION|KNOWLEDGE_SOURCE_PATH|KNOWLEDGE_IMPORT_ENABLED|FRONTEND_ORIGIN)=/' > "$baseline"

echo 'Building candidate; infrastructure is unchanged.'
docker build --pull -t "${IMAGE_NAME}:candidate" "$REPOSITORY_ROOT"
previous_stopped=false
previous_renamed=false
candidate_created=false
restore_previous_backend() {
  trap - ERR INT TERM
  echo 'Deployment failed; restoring the complete previous backend container.' >&2
  if [[ "$candidate_created" == true ]]; then docker rm -f "$CONTAINER_NAME" >/dev/null; fi
  if [[ "$previous_renamed" == true ]]; then docker rename "$ROLLBACK_NAME" "$CONTAINER_NAME"; fi
  if [[ "$previous_stopped" == true ]]; then docker start "$CONTAINER_NAME" >/dev/null; fi
  exit 1
}
trap restore_previous_backend ERR INT TERM
# Mark before stop: an interrupted stop must also attempt to restore availability.
previous_stopped=true
docker stop "$CONTAINER_NAME" >/dev/null
docker rename "$CONTAINER_NAME" "$ROLLBACK_NAME"
previous_renamed=true
# Create first, attach ALL original networks/aliases, then start the process.
docker create --name "$CONTAINER_NAME" --restart unless-stopped \
  --network "${networks[0]}" --env-file "$baseline" --env-file "$ENV_FILE" \
  -e RECORDING_STORAGE_PATH=/app/data/recordings \
  --mount "type=bind,src=${RECORDING_HOST_PATH},dst=/app/data/recordings" \
  "${IMAGE_NAME}:candidate" >/dev/null
candidate_created=true
for network in "${networks[@]}"; do
  # Reconnect primary too, so its custom aliases can be preserved consistently.
  if [[ "$network" == "${networks[0]}" ]]; then docker network disconnect "$network" "$CONTAINER_NAME"; fi
  printf -v alias_template '{{with index .NetworkSettings.Networks "%s"}}{{range .Aliases}}{{println .}}{{end}}{{end}}' "$network"
  mapfile -t aliases < <(docker inspect -f "$alias_template" "$ROLLBACK_NAME" | sed '/^$/d')
  args=()
  for alias in "${aliases[@]}"; do
    # Container-generated IDs are not application DNS names.
    if [[ ! "$alias" =~ ^[0-9a-f]{12,64}$ ]]; then args+=(--alias "$alias"); fi
  done
  docker network connect "${args[@]}" "$network" "$CONTAINER_NAME"
done
docker start "$CONTAINER_NAME" >/dev/null
healthy=false
for attempt in $(seq 1 150); do
  if docker exec "$CONTAINER_NAME" curl --max-time 10 --fail --silent "$HEALTH_URL" >/dev/null \
    && docker exec "$CONTAINER_NAME" curl --max-time 10 --fail --silent "$STATUS_URL" >/dev/null; then
    healthy=true; break
  fi
  [[ "$(docker inspect -f '{{.State.Running}}' "$CONTAINER_NAME")" == true ]] || break
  sleep 2
done
[[ "$healthy" == true ]] || restore_previous_backend
docker tag "${IMAGE_NAME}:candidate" "${IMAGE_NAME}:ec2"
trap - ERR INT TERM
echo "Backend healthy. Retained $ROLLBACK_NAME for recording/business acceptance; no images were pruned."
echo 'Finish acceptance or restore the old container before the next deployment.'

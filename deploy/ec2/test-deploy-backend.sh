#!/usr/bin/env bash
set -euo pipefail
SCRIPT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/deploy-backend.sh"
sandbox=$(mktemp -d)
trap 'status=$?; if [[ $status != 0 ]]; then cat "$sandbox/output" "$LOG"; fi; rm -rf -- "$sandbox"' EXIT
export DEPLOY_ROOT="$sandbox" APP_ENV_FILE="$sandbox/runtime.env" RECORDINGS="$sandbox/recordings"
mkdir "$RECORDINGS"
touch "$APP_ENV_FILE" "$sandbox/compose.yaml"
export LOG="$sandbox/commands" STATE="$sandbox/state"
docker() {
  printf '%s\n' "$*" >> "$LOG"
  case "$1" in
    inspect)
      if [[ "$2" == ai-repair-backend-rollback ]]; then [[ "$CASE" == retained ]]; return; fi
      local template="$3" target="${@: -1}"
      case "$template" in
        *'.State.Running'*)
          if [[ "$CASE" == healthfail && "$target" == ai-repair-backend && -f "$STATE" ]]; then echo false; else echo true; fi ;;
        *'.Mounts'*)
          if [[ "$template" == *'if ne .Destination'* ]]; then
            if [[ "$CASE" == extramount ]]; then echo /app/other-data; fi
            return 0
          fi
          if [[ "$CASE" == missingmount ]]; then echo ''; else printf 'bind|%s|true\n' "$RECORDINGS"; fi ;;
        *'.Aliases'*)
          [[ "$template" == *'"private-a"'* || "$template" == *'"private-b"'* ]] || return 2
          printf 'ai-repair-backend\ncustom-backend\nabcdef123456\n' ;;
        *'.NetworkSettings.Networks'*)
          if [[ "$CASE" == disconnected && "$target" == qdrant-id ]]; then echo other; else printf 'private-a\nprivate-b\n'; fi ;;
        *'.State.Health'*) echo healthy ;;
        *'.Config.Env'*) printf 'MYSQL_URL=jdbc:mysql://mysql:3306/production\nQDRANT_URL=http://qdrant:6333\n' ;;
        *) echo "Unexpected template $template" >&2; return 2 ;;
      esac ;;
    compose)
      [[ "$*" == *' ps -q mysql' ]] && { echo mysql-id; return; }
      [[ "$*" == *' ps -q qdrant' ]] && { echo qdrant-id; return; }
      return 2 ;;
    create) touch "$STATE" ;;
    exec) [[ "$CASE" != healthfail ]] ;;
    network) if [[ "$CASE" == connectfail && "$*" == *'connect --alias'* ]]; then return 1; fi ;;
    *) return 0 ;;
  esac
}
flock() { return 0; }
export -f docker flock
for CASE in success missingmount extramount disconnected retained connectfail healthfail; do
  export CASE
  : > "$LOG"; rm -f "$STATE"
  result=0
  bash "$SCRIPT" > "$sandbox/output" 2>&1 || result=$?
  if [[ "$CASE" == success ]]; then
    [[ "$result" == 0 ]]
    grep -Fq 'connect --alias ai-repair-backend --alias custom-backend private-a' "$LOG"
    grep -Fq 'connect --alias ai-repair-backend --alias custom-backend private-b' "$LOG"
    grep -Fq "src=$RECORDINGS,dst=/app/data/recordings" "$LOG"
    ! grep -Eq '^rm |^image prune|^update | -p ' "$LOG"
  else
    [[ "$result" != 0 ]]
    if [[ "$CASE" == connectfail || "$CASE" == healthfail ]]; then
      grep -Fxq 'rename ai-repair-backend-rollback ai-repair-backend' "$LOG"
      grep -Fxq 'start ai-repair-backend' "$LOG"
    else
      ! grep -Eq '^stop |^create |^build ' "$LOG"
    fi
  fi
  ! grep -Eq '^compose .* (up|down|restart)|^update ' "$LOG"
  echo "PASS $CASE"
done

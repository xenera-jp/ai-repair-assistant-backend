# Existing backend replacement only

`deploy-backend.sh` is not an infrastructure bootstrap script. It requires the existing backend and the original infrastructure Compose file. It only queries Compose for MySQL/Qdrant IDs and checks running/health state and shared networks. It never runs infrastructure up/down/update/restart.

Before stopping the backend it validates the existing writable recording bind mount, derives its host source from the old container, checks that the directory exists, and rejects a differing RECORDING_HOST_PATH. It does not create, chmod, chown or delete recording directories. Unexpected additional mounts cause an abort for explicit review.

All original private user-defined network memberships and custom aliases are attached before the new process starts. No host port is published. Preserve the actual deployment Compose file/project identity; do not replace it with another sample or create new infrastructure volumes.

Previously injected MYSQL/Qdrant/knowledge/frontend settings are carried forward into a permission-restricted temporary environment file, without printing them. The managed APP_ENV_FILE is applied second so explicit updates take precedence. The recording container path stays /app/data/recordings. Temporary configuration is removed on exit. Do not run with shell tracing enabled or capture secret configuration in deployment logs.

Deployment acquires a host lock. Failures after replacement starts restore the original container, including its mounts, networks and host-port configuration. A healthy candidate leaves the stopped `<name>-rollback` container intact; another deployment is refused until the rollback container is resolved. No image pruning occurs.

## Acceptance and rollback

Back up the database and recordings before release. Check historical recording playback/download and a new upload, plus normal API operations, before accepting. Process health does not validate these business operations. Application startup may apply Flyway migrations; restoring a container does not undo database changes or shared recording writes.

With the default container names, if business acceptance fails, suspend automatic deployments and run:

```sh
# Confirm these are the intended candidate and saved original container first.
docker inspect ai-repair-backend-rollback --format '{{.Image}} {{json .NetworkSettings.Networks}} {{json .Mounts}}'
docker stop ai-repair-backend
docker rm ai-repair-backend
docker rename ai-repair-backend-rollback ai-repair-backend
docker start ai-repair-backend
```

If acceptance succeeds and the agreed rollback window ends, remove only the stopped `ai-repair-backend-rollback` container. Keep image/configuration backups under the release retention policy. Never run down -v or delete recording directories. Infrastructure remains managed by its original Compose project.

## Local regression checks

Run `bash deploy/ec2/test-deploy-backend.sh`. It mocks Docker and flock, uses empty temporary deployment configuration, and never reads real environment files or contacts Docker/EC2. Cases cover multi-network/alias retention, missing mount, disconnected infrastructure, existing rollback container, network-attachment failure and health-check failure. Real deployment still needs EC2 permission/network/file-access checks and business acceptance.

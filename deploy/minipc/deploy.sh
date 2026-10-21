#!/usr/bin/env bash
set -euo pipefail
umask 077

ROOT=/opt/scds
TAG="${1:?Pass the full deployed commit SHA}"
JAR_SHA="${2:?Pass the expected jar SHA-256}"
[[ "$(id -u)" == 0 ]] || { echo "Run with deployment administrator privileges."; exit 1; }
[[ "$TAG" =~ ^[0-9a-f]{40}$ && "$JAR_SHA" =~ ^[0-9a-fA-F]{64}$ ]] \
  || { echo "Invalid release identifiers."; exit 1; }
[[ -f "$ROOT/compose.yaml" ]] || { echo "Missing isolated Compose configuration."; exit 1; }
command -v openssl >/dev/null
docker image inspect "scds-next:$TAG" >/dev/null
docker image inspect mysql:8.4 >/dev/null

ACTUAL_JAR_SHA="$(docker run --rm --pull=never --network none \
  --entrypoint sha256sum "scds-next:$TAG" /app/scds-next.jar | awk '{print $1}')"
[[ "${JAR_SHA,,}" == "$ACTUAL_JAR_SHA" ]] || { echo "Jar checksum mismatch."; exit 1; }

for container in scds-validation-mysql scds-validation-backend; do
  if docker container inspect "$container" >/dev/null 2>&1; then
    project="$(docker inspect --format '{{index .Config.Labels "com.docker.compose.project"}}' "$container")"
    [[ "$project" == scds-validation && -f "$ROOT/.deploy-state" ]] \
      || { echo "Existing unowned or partially deployed container; inspect before proceeding."; exit 1; }
  fi
done
if [[ ! -f "$ROOT/.deploy-state" ]]; then
  if docker volume inspect scds-validation_mysql_data >/dev/null 2>&1; then
    echo "Existing database volume without deployment state; refusing initialization."
    exit 1
  fi
  if ss -ltn '( sport = :11001 )' | tail -n +2 | grep -q .; then
    echo "Port 11001 is already occupied."
    exit 1
  fi
fi

mkdir -p "$ROOT/secrets"
chmod 700 "$ROOT" "$ROOT/secrets"
FIRST_BOOT=false
existing=0
for file in .env jwt-private.pem jwt-public.pem; do
  [[ ! -e "$ROOT/secrets/$file" ]] || existing=$((existing + 1))
done

# Stage 1: three credential files; never replace existing credentials.
if [[ "$existing" == 0 ]]; then
  [[ ! -f "$ROOT/.deploy-state" ]] || { echo "Missing established credentials."; exit 1; }
  FIRST_BOOT=true
  {
    printf 'SCDS_MYSQL_ROOT_PASSWORD=%s\n' "$(openssl rand -hex 32)"
    printf 'SCDS_MYSQL_PASSWORD=%s\n' "$(openssl rand -hex 32)"
    printf 'SCDS_ADMIN_USERNAME=admin\n'
    printf 'SCDS_ADMIN_PASSWORD=%s\n' "$(openssl rand -hex 24)"
  } > "$ROOT/secrets/.env"
  openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 \
    -out "$ROOT/secrets/jwt-private.pem" 2>/dev/null
  openssl pkey -in "$ROOT/secrets/jwt-private.pem" -pubout \
    -out "$ROOT/secrets/jwt-public.pem" 2>/dev/null
elif [[ "$existing" != 3 || ! -f "$ROOT/.deploy-state" ]]; then
  echo "Partial credential initialization; inspect state, do not regenerate or delete files."
  exit 1
fi

# The root-only file is generated with fixed keys and hexadecimal secret values.
source "$ROOT/secrets/.env"
[[ "${SCDS_MYSQL_PASSWORD:-}" =~ ^[0-9a-f]{64}$ \
  && "${SCDS_MYSQL_ROOT_PASSWORD:-}" =~ ^[0-9a-f]{64}$ \
  && "${SCDS_ADMIN_PASSWORD:-}" =~ ^[0-9a-f]{48}$ \
  && "${SCDS_ADMIN_USERNAME:-}" == admin ]] \
  || { echo "Unexpected protected credential format."; exit 1; }
chmod 600 "$ROOT/secrets/.env"
chown root:10001 "$ROOT/secrets/jwt-private.pem" "$ROOT/secrets/jwt-public.pem"
chmod 640 "$ROOT/secrets/jwt-private.pem" "$ROOT/secrets/jwt-public.pem"

# Stage 2: one configuration file. Secrets are neither arguments nor log output.
render_configuration() {
  {
    cat <<'CONFIG'
server.address=0.0.0.0
server.forward-headers-strategy=none
spring.datasource.url=jdbc:mysql://mysql:3306/scds_next?rewriteBatchedStatements=true
spring.datasource.username=scds_app
spring.datasource.hikari.maximum-pool-size=4
spring.datasource.hikari.data-source-properties.connectionTimeZone=UTC
spring.datasource.hikari.data-source-properties.forceConnectionTimeZoneToSession=true
spring.flyway.clean-disabled=true
spring.flyway.out-of-order=false
scds.deployment-mode=PRIVATE
scds.auth.jwt.signing-enabled=true
scds.auth.jwt.issuer=scds-minipc-validation
scds.auth.jwt.jwks-uri=
scds.auth.jwt.public-key-file=/run/scds/jwt-public.pem
scds.auth.jwt.private-key-file=/run/scds/jwt-private.pem
CONFIG
    printf 'spring.datasource.password=%s\n' "$SCDS_MYSQL_PASSWORD"
    if [[ "$FIRST_BOOT" == true ]]; then
      printf 'scds.admin.username=%s\nscds.admin.password=%s\n' \
        "$SCDS_ADMIN_USERNAME" "$SCDS_ADMIN_PASSWORD"
    else
      printf 'scds.admin.username=\nscds.admin.password=\n'
    fi
  } > "$ROOT/secrets/application.properties"
  chown root:10001 "$ROOT/secrets/application.properties"
  chmod 640 "$ROOT/secrets/application.properties"
}
render_configuration

export SCDS_IMAGE_TAG="$TAG"
COMPOSE=(docker compose --env-file "$ROOT/secrets/.env" -f "$ROOT/compose.yaml")
"${COMPOSE[@]}" config --quiet
"${COMPOSE[@]}" up -d --wait --wait-timeout 240
curl -fsS --max-time 5 http://127.0.0.1:11001/actuator/health >/dev/null

if [[ "$FIRST_BOOT" == true ]]; then
  ADMIN_COUNT="$(docker exec scds-validation-mysql sh -c \
    'MYSQL_PWD=$MYSQL_PASSWORD mysql -u"$MYSQL_USER" "$MYSQL_DATABASE" -N -B -e "SELECT COUNT(*) FROM t_account WHERE username='\''admin'\'' AND enabled=1;"')"
  [[ "$ADMIN_COUNT" == 1 ]] || { echo "Initial administrator not confirmed; retain state for inspection."; exit 1; }
  # Remove bootstrap credentials from runtime configuration, not the recovery file.
  FIRST_BOOT=false
  render_configuration
  "${COMPOSE[@]}" up -d --no-deps --force-recreate --wait --wait-timeout 180 backend
  curl -fsS --max-time 5 http://127.0.0.1:11001/actuator/health >/dev/null
fi

# Stage 3: non-secret release state only. No database rollback or destructive cleanup.
{
  printf 'commit=%s\njar_sha256=%s\n' "$TAG" "$ACTUAL_JAR_SHA"
  printf 'image_id=%s\n' "$(docker image inspect "scds-next:$TAG" --format '{{.Id}}')"
  printf 'deployed_at=%s\nmode=validation-loopback-only\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)"
} > "$ROOT/.deploy-state"
echo "SCDS validation deployment healthy: $TAG"
echo "Initial administrator credentials remain only in /opt/scds/secrets/.env (root-only)."

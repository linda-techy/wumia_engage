#!/usr/bin/env bash
# Deploy the current checkout to production (46.202.164.251), from a developer PC.
#   deploy/prod/deploy.sh [ssh-key]
# 1. compiles the three services (installDist) and the console (ng build) locally,
# 2. ships one bundle, 3. builds the runtime images on the server,
# 4. runs migrations (the migrate job) and restarts the services.
# Run the full test build first (./gradlew build); this script does not.
set -euo pipefail
HOST=root@46.202.164.251
KEY=${1:-/l/projects/wumika-ssh/wumika-root-2026-10}
SSH="ssh -i $KEY -o BatchMode=yes -o IdentitiesOnly=yes"
SCP="scp -q -i $KEY -o BatchMode=yes -o IdentitiesOnly=yes"
cd "$(dirname "$0")/../.."
REV=$(git rev-parse --short HEAD)
STAGE=$(mktemp -d)

echo "== build $REV"
./gradlew -q --no-daemon :ingest-api:installDist :admin-api:installDist :worker:installDist -x test
(cd admin-ui && npx ng build >/dev/null)
for m in ingest-api admin-api worker; do cp -r "$m/build/install/$m" "$STAGE/$m"; done
cp -r admin-ui/dist/admin-ui/browser "$STAGE/browser"
cp deploy/prod/Dockerfile.runtime deploy/prod/Dockerfile.console deploy/prod/console.nginx.conf \
   deploy/prod/docker-compose.yml deploy/prod/backup.sh "$STAGE/"
echo "$REV" > "$STAGE/REVISION"
tar -C "$STAGE" -czf "$STAGE.tgz" .

echo "== ship $(du -h "$STAGE.tgz" | cut -f1)"
$SCP "$STAGE.tgz" "$HOST:/opt/wumika/release/bundle.tgz"
$SSH "$HOST" "set -e
  cd /opt/wumika/release && rm -rf b && mkdir b && tar -xzf bundle.tgz -C b && cd b
  for m in ingest-api admin-api worker; do
    docker build -q -f Dockerfile.runtime --build-arg MODULE=\$m -t wumika/\$m:prod -t wumika/\$m:$REV . >/dev/null
  done
  docker build -q -f Dockerfile.console -t wumika/console:prod -t wumika/console:$REV . >/dev/null
  install -m 640 docker-compose.yml /opt/wumika/docker-compose.yml
  install -m 750 backup.sh /usr/local/bin/wumika-backup
  echo $REV > /opt/wumika/REVISION
  cd /opt/wumika
  test -f .env || { echo '/opt/wumika/.env missing'; exit 1; }
  # Database first, then migrations as an explicit step (an exited one-shot job is
  # not re-run by 'up'), then every app container recreated on the new images.
  docker compose up -d --remove-orphans db
  docker compose run --rm -T migrate
  docker compose up -d --force-recreate --no-deps ingest admin worker console
  docker image prune -f >/dev/null
  rm -rf /opt/wumika/release/b /opt/wumika/release/bundle.tgz"
rm -rf "$STAGE" "$STAGE.tgz"
echo "== deployed $REV"

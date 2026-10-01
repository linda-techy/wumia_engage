#!/bin/sh
# Nightly production database backup (cron, deploy user; see deploy/REBUILD.md).
#
#   1. pg_dump (custom format) from the prod db container
#   2. check it reads back (pg_restore --list)
#   3. encrypt with age to /opt/wumika-prod/backup.pub; the private key is kept
#      OFF this server (a backup the server can decrypt is no protection if the
#      server is compromised)
#   4. copy off-server with rclone to $BACKUP_REMOTE (e.g. gdrive:wumika-backups)
#   5. keep 7 days here, 30 days remote
#
# Restore: rclone copy the .dump.age back, `age -d -i <private key>`, then
#   docker compose exec -T db pg_restore -U "$DB_USER" -d "$DB_NAME" --clean < file.dump
set -eu
cd /opt/wumika-prod
umask 077
log() { logger -t wumika-backup "$*"; echo "$*"; }
fail() { log "FAILED: $*"; exit 1; }

# Read only this one value: .env is not shell syntax and is never sourced.
BACKUP_REMOTE=$(grep -E '^BACKUP_REMOTE=' .env | tail -1 | cut -d= -f2-)
test -n "$BACKUP_REMOTE" || fail "set BACKUP_REMOTE in .env (rclone remote:path)"
test -s backup.pub || fail "backup.pub (age public key) missing"
command -v age >/dev/null || fail "age not installed"
command -v rclone >/dev/null || fail "rclone not installed"

stamp=$(date -u +%Y%m%d-%H%M)
dump="backups/wumika-prod-$stamp.dump"
mkdir -p backups

docker compose exec -T db sh -c 'pg_dump -U "$POSTGRES_USER" -d "$POSTGRES_DB" -Fc' > "$dump" || fail "pg_dump"
docker compose exec -T db pg_restore --list < "$dump" > /dev/null || fail "dump does not read back"
age -R backup.pub -o "$dump.age" "$dump" || fail "encrypt"
rm -f "$dump"

rclone copy "$dump.age" "$BACKUP_REMOTE" || fail "upload to $BACKUP_REMOTE"
find backups -name 'wumika-prod-*.dump.age' -mtime +7 -delete
rclone delete --min-age 30d "$BACKUP_REMOTE" || log "warning: remote retention cleanup failed"

log "ok: $(basename "$dump").age ($(wc -c < "$dump.age") bytes) -> $BACKUP_REMOTE"

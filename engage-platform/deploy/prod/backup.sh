#!/usr/bin/env bash
# Nightly PostgreSQL backup of the production database (cron: /etc/cron.d/wumika-backup).
# Compressed dumps in /opt/wumika/backups, 14 days kept. Copy them off the server
# regularly: a backup on the same disk does not survive losing the server.
set -euo pipefail
cd /opt/wumika
set -a; . ./.env; set +a
out="/opt/wumika/backups/engage-$(date -u +%Y%m%d-%H%M).sql.gz"
docker compose exec -T db pg_dump -U "$DB_USER" -d "$DB_NAME" --no-owner | gzip > "$out.part"
mv "$out.part" "$out"
chmod 600 "$out"
find /opt/wumika/backups -name 'engage-*.sql.gz' -mtime +14 -delete
echo "$(date -u +%FT%TZ) backup ok: $out ($(du -h "$out" | cut -f1))"

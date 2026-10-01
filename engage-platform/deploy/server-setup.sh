#!/usr/bin/env bash
# One-time setup of the Wumika server on a FRESH Ubuntu install (24.04 or 22.04).
# Dev and prod share this server (decided 2026-10-01; deploy/REBUILD.md):
#   dev   /opt/wumika        engage-dev.wumika.com -> 127.0.0.1:8081   (push to dev)
#   prod  /opt/wumika-prod   engage.wumika.com     -> 127.0.0.1:9081   (push to main + approval)
#
# Run as root:   sudo bash server-setup.sh 'ssh-ed25519 AAAA... github-deploy'
# Safe to re-run. TLS is requested only for domains whose DNS already points here.
set -euo pipefail

DEV_DOMAIN=engage-dev.wumika.com
PROD_DOMAIN=engage.wumika.com
CERT_EMAIL=${CERT_EMAIL:-nithinkr24@gmail.com}
DEPLOY_PUBKEY=${1:?usage: server-setup.sh '<public key GitHub Actions deploys with>'}

[[ $EUID -eq 0 ]] || { echo "run as root"; exit 1; }
export DEBIAN_FRONTEND=noninteractive

echo "== 1. Updates, automatic security updates, fail2ban"
apt-get update
apt-get -y upgrade
apt-get install -y unattended-upgrades fail2ban ca-certificates curl dnsutils age rclone
dpkg-reconfigure -f noninteractive unattended-upgrades
systemctl enable --now fail2ban

echo "== 2. Docker"
if ! command -v docker &>/dev/null; then
  install -m 0755 -d /etc/apt/keyrings
  curl -fsSL https://download.docker.com/linux/ubuntu/gpg -o /etc/apt/keyrings/docker.asc
  chmod a+r /etc/apt/keyrings/docker.asc
  . /etc/os-release
  echo "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.asc] https://download.docker.com/linux/ubuntu ${VERSION_CODENAME} stable" \
    > /etc/apt/sources.list.d/docker.list
  apt-get update
  apt-get install -y docker-ce docker-ce-cli containerd.io docker-compose-plugin
fi
systemctl enable --now docker

echo "== 3. deploy user (GitHub Actions) and the two app folders"
id deploy &>/dev/null || useradd --create-home --shell /bin/bash deploy
usermod -aG docker deploy          # docker group = root-equivalent: this key is the server's crown jewel
install -d -m 700 -o deploy -g deploy /home/deploy/.ssh
grep -qxF "$DEPLOY_PUBKEY" /home/deploy/.ssh/authorized_keys 2>/dev/null \
  || echo "$DEPLOY_PUBKEY" >> /home/deploy/.ssh/authorized_keys
chown deploy:deploy /home/deploy/.ssh/authorized_keys
chmod 600 /home/deploy/.ssh/authorized_keys

for dir in /opt/wumika /opt/wumika-prod; do
  install -d -m 750 -o deploy -g deploy "$dir" "$dir/secrets"
  # admin-api runs as uid 10001 (see Dockerfile) and writes its RS256 key here.
  install -d -m 700 -o 10001 -g 10001 "$dir/keys"
done
install -d -m 700 -o deploy -g deploy /opt/wumika-prod/backups

echo "== 4. nginx + TLS"
apt-get install -y nginx certbot python3-certbot-nginx
site() {   # domain port
  cat > "/etc/nginx/sites-available/$1" <<NGINX
server {
    listen 80;
    listen [::]:80;
    server_name $1;
    client_max_body_size 2m;
    location / {
        proxy_pass http://127.0.0.1:$2;
        proxy_set_header Host \$host;
        proxy_set_header X-Real-IP \$remote_addr;
        proxy_set_header X-Forwarded-For \$proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto \$scheme;
        proxy_read_timeout 10s;
    }
}
NGINX
  ln -sf "/etc/nginx/sites-available/$1" "/etc/nginx/sites-enabled/$1"
}
site "$DEV_DOMAIN" 8081
site "$PROD_DOMAIN" 9081
rm -f /etc/nginx/sites-enabled/default
nginx -t && systemctl reload nginx

MY_IP=$(curl -fsS https://api.ipify.org || true)
for d in "$DEV_DOMAIN" "$PROD_DOMAIN"; do
  if [[ -n "$MY_IP" && "$(dig +short A "$d" | tail -1)" == "$MY_IP" ]]; then
    certbot --nginx --non-interactive --agree-tos -m "$CERT_EMAIL" -d "$d" --redirect
  else
    echo "   skipping TLS for $d: its DNS A record does not point to $MY_IP yet (re-run later)"
  fi
done

echo "== 5. Firewall: SSH, HTTP, HTTPS only"
apt-get install -y ufw
ufw allow OpenSSH
ufw allow 'Nginx Full'
ufw --force enable

echo "== 6. Nightly prod backup, 02:00 IST (20:30 UTC), as deploy"
CRON_LINE='30 20 * * * /opt/wumika-prod/backup.sh >> /opt/wumika-prod/backups/backup.log 2>&1'
( crontab -u deploy -l 2>/dev/null | grep -vF '/opt/wumika-prod/backup.sh' ; echo "$CRON_LINE" ) | crontab -u deploy -

cat <<EOF

Done. Manual steps left (deploy/REBUILD.md has the full list):
  sudo -iu deploy
  nano /opt/wumika/.env              # dev:  deploy/.env.example, values from config/devstore.env
  nano /opt/wumika-prod/.env         # prod: live values, all fresh; BACKUP_REMOTE set
  chmod 600 /opt/wumika/.env /opt/wumika-prod/.env
  rclone config                      # the off-server remote named in BACKUP_REMOTE
  nano /opt/wumika-prod/backup.pub   # the age PUBLIC key; keep the private key off this server
Then push to dev (and later main); GitHub Actions deploys.
EOF

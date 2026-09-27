#!/usr/bin/env bash
# One-time setup of the Wumika dev server (Ubuntu). Run as root:
#   sudo bash server-setup.sh 'ssh-ed25519 AAAA... github-deploy'
#
# Works on a freshly reinstalled OS (recommended) and on the current box,
# where it first removes Walldot. Walldot was backed up on 2026-09-26
# (wumika-ssh/backups/backup-20260926); everything removed here is in it.
# Safe to re-run.
set -euo pipefail

DOMAIN=engage-dev.wumika.com
CERT_EMAIL=${CERT_EMAIL:-nithinkr24@gmail.com}
DEPLOY_PUBKEY=${1:?usage: server-setup.sh '<public key GitHub Actions deploys with>'}

[[ $EUID -eq 0 ]] || { echo "run as root"; exit 1; }

echo "== 1. Remove Walldot"
for u in backenduser ftpuser; do
  if id "$u" &>/dev/null && command -v pm2 &>/dev/null; then
    for app in walldot-customer-api walldot-portal-api walldot-ssr; do
      sudo -iu "$u" pm2 delete "$app" 2>/dev/null || true
    done
    sudo -iu "$u" pm2 save --force 2>/dev/null || true
  fi
done
# ftpuser's crontab ran /tmp/kernal every minute: not part of Walldot, and
# the usual sign of a miner. Remove it and whatever it started.
crontab -r -u ftpuser 2>/dev/null || true
pkill -f /tmp/kernal 2>/dev/null || true
rm -f /tmp/kernal
for site in walldotbuilders.com api.walldotbuilders.com app.walldotbuilders.com \
            cust-api.walldotbuilders.com portal.walldotbuilders.com; do
  rm -f "/etc/nginx/sites-enabled/$site" "/etc/nginx/sites-available/$site"
done
if command -v certbot &>/dev/null; then
  for cert in api.walldotbuilders.com app.walldotbuilders.com cust-api.walldotbuilders.com \
              portal.walldotbuilders.com www.walldotbuilders.com; do
    certbot delete --non-interactive --cert-name "$cert" 2>/dev/null || true
  done
fi
rm -rf /home/ftpuser/var/www/app/walldotbuilders
# The host Postgres also holds staygetherStagDB and youtube_automation, which
# are not Walldot, so it is left running. Wumika uses its own container.
# To drop Walldot's databases too (they are in the backup):
#   sudo -u postgres dropdb wdTestDB; sudo -u postgres dropdb wdbuilders

echo "== 2. Docker"
if ! command -v docker &>/dev/null; then
  apt-get update
  apt-get install -y ca-certificates curl
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

echo "== 3. deploy user and /opt/wumika"
id deploy &>/dev/null || useradd --create-home --shell /bin/bash deploy
usermod -aG docker deploy
install -d -m 700 -o deploy -g deploy /home/deploy/.ssh
grep -qxF "$DEPLOY_PUBKEY" /home/deploy/.ssh/authorized_keys 2>/dev/null \
  || echo "$DEPLOY_PUBKEY" >> /home/deploy/.ssh/authorized_keys
chown deploy:deploy /home/deploy/.ssh/authorized_keys
chmod 600 /home/deploy/.ssh/authorized_keys

install -d -m 750 -o deploy -g deploy /opt/wumika
# admin-api runs as uid 10001 (see Dockerfile) and writes its RS256 key here.
install -d -m 700 -o 10001 -g 10001 /opt/wumika/keys

echo "== 4. nginx + TLS for $DOMAIN"
apt-get install -y nginx certbot python3-certbot-nginx
cat > "/etc/nginx/sites-available/$DOMAIN" <<'NGINX'
server {
    listen 80;
    listen [::]:80;
    server_name engage-dev.wumika.com;
    client_max_body_size 2m;
    location / {
        proxy_pass http://127.0.0.1:8081;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
        proxy_read_timeout 10s;
    }
}
NGINX
ln -sf "/etc/nginx/sites-available/$DOMAIN" "/etc/nginx/sites-enabled/$DOMAIN"
nginx -t && systemctl reload nginx
# Needs the DNS A record for $DOMAIN pointing here first.
certbot --nginx --non-interactive --agree-tos -m "$CERT_EMAIL" -d "$DOMAIN" --redirect

echo "== 5. Firewall: SSH, HTTP, HTTPS only"
if command -v ufw &>/dev/null; then
  ufw allow OpenSSH
  ufw allow 'Nginx Full'
  ufw --force enable
fi

cat <<EOF

Done. Last manual step, as deploy:
  sudo -iu deploy
  nano /opt/wumika/.env        # from deploy/.env.example, values from config/devstore.env
  chmod 600 /opt/wumika/.env
Then push to the dev branch; GitHub Actions deploys.
EOF

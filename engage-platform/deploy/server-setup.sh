#!/usr/bin/env bash
# One-time setup of the Wumika DEV server on a FRESH Ubuntu install (24.04 or 22.04).
#   /opt/wumika   engage-dev.wumika.com -> 127.0.0.1:8081   (deploys on push to dev)
# Production goes to AWS later (decided 2026-10-01), not on this server.
#
# Run as root:   sudo bash server-setup.sh 'ssh-ed25519 AAAA... github-deploy'
# Safe to re-run. TLS is requested only for domains whose DNS already points here.
set -euo pipefail

DEV_DOMAIN=engage-dev.wumika.com
CERT_EMAIL=${CERT_EMAIL:-nithinkr24@gmail.com}
DEPLOY_PUBKEY=${1:?usage: server-setup.sh '<public key GitHub Actions deploys with>'}

[[ $EUID -eq 0 ]] || { echo "run as root"; exit 1; }
export DEBIAN_FRONTEND=noninteractive

echo "== 1. Updates, automatic security updates, fail2ban"
apt-get update
apt-get -y upgrade
apt-get install -y unattended-upgrades fail2ban ca-certificates curl dnsutils
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

echo "== 3. deploy user (GitHub Actions) and the app folder"
id deploy &>/dev/null || useradd --create-home --shell /bin/bash deploy
usermod -aG docker deploy          # docker group = root-equivalent: this key is the server's crown jewel
install -d -m 700 -o deploy -g deploy /home/deploy/.ssh
grep -qxF "$DEPLOY_PUBKEY" /home/deploy/.ssh/authorized_keys 2>/dev/null \
  || echo "$DEPLOY_PUBKEY" >> /home/deploy/.ssh/authorized_keys
chown deploy:deploy /home/deploy/.ssh/authorized_keys
chmod 600 /home/deploy/.ssh/authorized_keys

install -d -m 750 -o deploy -g deploy /opt/wumika /opt/wumika/secrets
# admin-api runs as uid 10001 (see Dockerfile) and writes its RS256 key here.
install -d -m 700 -o 10001 -g 10001 /opt/wumika/keys

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
rm -f /etc/nginx/sites-enabled/default
nginx -t && systemctl reload nginx

MY_IP=$(curl -fsS https://api.ipify.org || true)
d="$DEV_DOMAIN"
if [[ -n "$MY_IP" && "$(dig +short A "$d" | tail -1)" == "$MY_IP" ]]; then
  certbot --nginx --non-interactive --agree-tos -m "$CERT_EMAIL" -d "$d" --redirect
else
  echo "   skipping TLS for $d: its DNS A record does not point to $MY_IP yet (re-run later)"
fi

echo "== 5. Firewall: SSH, HTTP, HTTPS only"
apt-get install -y ufw
ufw allow OpenSSH
ufw allow 'Nginx Full'
ufw --force enable

cat <<EOF

Done. Manual steps left (deploy/REBUILD.md has the full list):
  sudo -iu deploy
  nano /opt/wumika/.env              # deploy/.env.example, values from config/devstore.env
  chmod 600 /opt/wumika/.env
Then push to dev; GitHub Actions deploys.
EOF

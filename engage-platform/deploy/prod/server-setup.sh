#!/usr/bin/env bash
# One-time setup of the Wumika PRODUCTION server (46.202.164.251) on a fresh
# Ubuntu 22.04/24.04. Safe to re-run.
#
#   engage.wumika.com -> 127.0.0.1:8081  ingest-api: webhooks, storefront, pixel, media
#   admin.wumika.com  -> 127.0.0.1:8090  console container (admin UI + /api -> admin-api)
#
# Run as root:   bash server-setup.sh
set -euo pipefail

ENGAGE=engage.wumika.com
ADMIN=admin.wumika.com
CERT_EMAIL=${CERT_EMAIL:-nithinkr24@gmail.com}
[[ $EUID -eq 0 ]] || { echo "run as root"; exit 1; }
export DEBIAN_FRONTEND=noninteractive

echo "== 1. Updates, automatic security updates, fail2ban"
apt-get update -q
apt-get -y -q upgrade
apt-get install -y -q unattended-upgrades fail2ban ca-certificates curl dnsutils jq
dpkg-reconfigure -f noninteractive unattended-upgrades
cat > /etc/fail2ban/jail.d/sshd.local <<'EOF'
[sshd]
enabled = true
maxretry = 5
bantime = 1h
EOF
systemctl enable --now fail2ban
systemctl restart fail2ban

echo "== 2. SSH: keys only"
sort -u -o /root/.ssh/authorized_keys /root/.ssh/authorized_keys
cat > /etc/ssh/sshd_config.d/10-hardening.conf <<'EOF'
PermitRootLogin prohibit-password
PasswordAuthentication no
KbdInteractiveAuthentication no
EOF
sshd -t && systemctl reload ssh

echo "== 3. Swap (1 vCPU / 4 GB: room for a sale-day burst)"
if ! swapon --show | grep -q /swapfile; then
  fallocate -l 2G /swapfile && chmod 600 /swapfile && mkswap /swapfile && swapon /swapfile
  grep -q '^/swapfile' /etc/fstab || echo '/swapfile none swap sw 0 0' >> /etc/fstab
fi
sysctl -w vm.swappiness=10 >/dev/null
echo 'vm.swappiness=10' > /etc/sysctl.d/90-wumika.conf

echo "== 4. Docker"
if ! command -v docker &>/dev/null; then
  install -m 0755 -d /etc/apt/keyrings
  curl -fsSL https://download.docker.com/linux/ubuntu/gpg -o /etc/apt/keyrings/docker.asc
  chmod a+r /etc/apt/keyrings/docker.asc
  . /etc/os-release
  echo "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.asc] https://download.docker.com/linux/ubuntu ${VERSION_CODENAME} stable" \
    > /etc/apt/sources.list.d/docker.list
  apt-get update -q
  apt-get install -y -q docker-ce docker-ce-cli containerd.io docker-compose-plugin
fi
cat > /etc/docker/daemon.json <<'EOF'
{ "log-driver": "json-file", "log-opts": { "max-size": "20m", "max-file": "5" } }
EOF
systemctl enable docker && systemctl restart docker

echo "== 5. App folders"
install -d -m 750 /opt/wumika /opt/wumika/secrets /opt/wumika/backups /opt/wumika/release
install -d -m 700 -o 10001 -g 10001 /opt/wumika/keys     # admin-api (uid 10001) writes its JWT key here

echo "== 6. nginx"
apt-get install -y -q nginx certbot python3-certbot-nginx
cat > /etc/nginx/conf.d/00-wumika.conf <<'EOF'
server_tokens off;
limit_req_zone $binary_remote_addr zone=console:10m rate=10r/s;
EOF
site() {   # domain port extra
  cat > "/etc/nginx/sites-available/$1" <<NGINX
server {
    listen 80;
    listen [::]:80;
    server_name $1;
    client_max_body_size 2m;
    add_header X-Content-Type-Options nosniff always;
    add_header Referrer-Policy strict-origin-when-cross-origin always;
    location / {
        $3
        proxy_pass http://127.0.0.1:$2;
        proxy_set_header Host \$host;
        proxy_set_header X-Real-IP \$remote_addr;
        proxy_set_header X-Forwarded-For \$proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto \$scheme;
        proxy_read_timeout 30s;
    }
}
NGINX
  ln -sf "/etc/nginx/sites-available/$1" "/etc/nginx/sites-enabled/$1"
}
site "$ENGAGE" 8081 ""
site "$ADMIN" 8090 "limit_req zone=console burst=40 nodelay; add_header X-Frame-Options DENY always;"
rm -f /etc/nginx/sites-enabled/default
nginx -t && systemctl reload nginx

echo "== 7. TLS"
MY_IP=$(curl -fsS -4 https://api.ipify.org || true)
for d in "$ENGAGE" "$ADMIN"; do
  if [[ -n "$MY_IP" && "$(dig +short A "$d" @8.8.8.8 | tail -1)" == "$MY_IP" ]]; then
    certbot --nginx --non-interactive --agree-tos -m "$CERT_EMAIL" -d "$d" --redirect --keep-until-expiring
  else
    echo "   skipping TLS for $d: DNS does not point to $MY_IP yet (re-run later)"
  fi
done
systemctl enable --now certbot.timer 2>/dev/null || true

echo "== 8. Firewall: SSH, HTTP, HTTPS only"
apt-get install -y -q ufw
ufw allow OpenSSH
ufw allow 'Nginx Full'
ufw --force enable

echo "== 9. Nightly database backup (03:15 IST = 21:45 UTC), 14 days kept"
install -m 750 /opt/wumika/release/backup.sh /usr/local/bin/wumika-backup 2>/dev/null || true
echo '45 21 * * * root /usr/local/bin/wumika-backup >> /var/log/wumika-backup.log 2>&1' > /etc/cron.d/wumika-backup

echo "Done."

# Server rebuild and prod go-live

The server (46.202.164.251) ran a crypto-miner cron job (`/tmp/kernal`) and had Postgres open to the internet. Both were cleaned on 2026-09-27, but the OS was never reinstalled, and about 2.3 GB of memory in use cannot be accounted for by visible processes. **It is rebuilt before any production data goes on it** (decided 2026-10-01). Dev and prod then share it, with no staging.

## A. Before wiping (nothing irreplaceable is on it)

- Dev data is test data; the dev database and push subscriptions are recreated by use.
- Every secret on it is either in `config/devstore.env` locally or is rotated in step E.

## B. Hosting panel (you)

1. **Upgrade the plan if possible: 2 vCPU / 8 GB RAM.** Today it is 1 vCPU / 3.9 GB, and dev + prod under their memory caps need about 3.4 GB.
2. **Reinstall: Ubuntu 24.04 LTS**, a new root password, your own SSH public key if the panel offers it.
3. Keep the same IP if offered; otherwise update DNS for `engage-dev.wumika.com` and `engage.wumika.com`.

## C. New deploy key (Claude can do this locally)

The reinstall changes the server's host key, and the old deploy key must not be trusted again.

```sh
ssh-keygen -t ed25519 -N '' -C github-deploy -f wumika-ssh/wumika-deploy-2026-10
ssh-keyscan 46.202.164.251        # new DEPLOY_KNOWN_HOSTS
```
GitHub → Settings → Secrets and variables → Actions: replace `DEPLOY_SSH_KEY` and `DEPLOY_KNOWN_HOSTS`.

## D. Provision

```sh
scp deploy/server-setup.sh root@46.202.164.251:/root/
ssh root@46.202.164.251 "bash /root/server-setup.sh '$(cat wumika-ssh/wumika-deploy-2026-10.pub)'"
```
It sets up updates, fail2ban, Docker, the `deploy` user, `/opt/wumika` + `/opt/wumika-prod`, nginx for both domains (TLS where DNS already points here), the firewall (22/80/443 only) and the 02:00 IST backup cron. No host Postgres, no FTP.

## E. Rotate what the old server held

| Secret | Where to rotate |
|---|---|
| Dev Shopify app client secret (`SHOPIFY_API_SECRET`) | Dev Dashboard → wumikaEngage-dev → Settings → rotate; update `config/devstore.env` |
| Firebase service account (also pasted in chat once) | Firebase → Project settings → Service accounts → new key; delete the old one |
| `DB_PASSWORD`, `ADMIN_MFA_KEY`, `PIXEL_WRITE_KEY` (+ re-run `webPixelCreate`), `METRICS_TOKEN` | generate fresh |
| Razorpay **test** webhook secret | Razorpay (Test Mode) → Webhooks |

## F. Dev back up

`/opt/wumika/.env` from `deploy/.env.example` with dev values, mode 600, the Firebase file at `/opt/wumika/secrets/firebase-service-account.json` (mode 400, owner 10001). Push to `dev`; CI deploys; `migrate` runs first. Check `https://engage-dev.wumika.com/health`.

## G. Prod go-live (later, step by step, each confirmed)

1. DNS `engage.wumika.com` → the server; re-run `server-setup.sh` for its certificate.
2. GitHub → Settings → Environments → **production** → Required reviewers: you.
3. `/opt/wumika-prod/.env`: **live** values, all fresh, never copied from dev. Live Shopify app credentials, Razorpay **live** mode, and `CUSTOMER_ALLOWLIST_EMAILS` still restricted to the approved test customer until you approve everyone.
4. Backups: `age-keygen -o wumika-backup.key` on **your** computer; put only the public key in `/opt/wumika-prod/backup.pub`; keep the `.key` file somewhere safe off the server. `rclone config` a remote (Google Drive, Backblaze, S3) and set `BACKUP_REMOTE`. Run `/opt/wumika-prod/backup.sh` once by hand, then **restore it into a scratch database** to prove it works.
5. Merge `dev` into `main`, approve the run, check `https://engage.wumika.com/health`.
6. Point the live store's app webhooks (and later Shiprocket's) at `engage.wumika.com`.

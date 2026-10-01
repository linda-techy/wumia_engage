# Dev server rebuild

The server (46.202.164.251) ran a crypto-miner cron job (`/tmp/kernal`) and had Postgres open to the internet. Both were cleaned on 2026-09-27, but the OS was never reinstalled, and about 2.3 GB of memory in use cannot be accounted for by visible processes. Rebuilding it is recommended (decided 2026-10-01). It is the **dev** server only: production goes to AWS later, with its own setup.

## A. Before wiping (nothing irreplaceable is on it)

- Dev data is test data; the dev database and push subscriptions are recreated by use.
- Every secret on it is either in `config/devstore.env` locally or is rotated in step E.

## B. Hosting panel (you)

1. Today it is 1 vCPU / 3.9 GB; dev under its memory caps needs about 1.6 GB, so the current size is enough.
2. **Reinstall: Ubuntu 24.04 LTS**, a new root password, your own SSH public key if the panel offers it.
3. Keep the same IP if offered; otherwise update DNS for `engage-dev.wumika.com`.

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
It sets up updates, fail2ban, Docker, the `deploy` user, `/opt/wumika`, nginx with TLS, and the firewall (22/80/443 only). No host Postgres, no FTP.

## E. Rotate what the old server held

| Secret | Where to rotate |
|---|---|
| Dev Shopify app client secret (`SHOPIFY_API_SECRET`) | Dev Dashboard → wumikaEngage-dev → Settings → rotate; update `config/devstore.env` |
| Firebase service account (also pasted in chat once) | Firebase → Project settings → Service accounts → new key; delete the old one |
| `DB_PASSWORD`, `ADMIN_MFA_KEY`, `PIXEL_WRITE_KEY` (+ re-run `webPixelCreate`), `METRICS_TOKEN` | generate fresh |
| Razorpay **test** webhook secret | Razorpay (Test Mode) → Webhooks |

## F. Dev back up

`/opt/wumika/.env` from `deploy/.env.example` with dev values, mode 600, the Firebase file at `/opt/wumika/secrets/firebase-service-account.json` (mode 400, owner 10001). Push to `dev`; CI deploys; `migrate` runs first. Check `https://engage-dev.wumika.com/health`.

## G. Production

Production goes to **AWS** later (decided 2026-10-01): managed Postgres in `ap-south-1`, secrets from the AWS secret manager, migrations as the same `ingest-api migrate` job, encrypted off-server backups. It gets its own plan when it is time; nothing here is reused for it except the images and the migrate job.

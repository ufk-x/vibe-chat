#!/usr/bin/env bash
# Run on a dedicated Ubuntu 24.04 host after copying server/ and deploy/ to /opt/vibe-chat.
set -euo pipefail
export DEBIAN_FRONTEND=noninteractive
VIBE_DOMAIN=${1:?Usage: install.sh public-domain}
if [[ ! "$VIBE_DOMAIN" =~ ^[a-zA-Z0-9-]+(\.[a-zA-Z0-9-]+)+$ ]]; then
    echo 'Provide only the DNS name, without https:// or a path.' >&2
    exit 2
fi
if [[ $EUID != 0 ]]; then
    echo 'Run this script with sudo on the deployment server.' >&2
    exit 2
fi
if [[ ! -f /opt/vibe-chat/server/src/main.mjs || ! -f /opt/vibe-chat/deploy/backup.sh ]]; then
    echo 'Copy server/ and deploy/ into /opt/vibe-chat first.' >&2
    exit 2
fi
case "$(uname -m)" in
    x86_64) VIBE_ARCH=x64 ;;
    aarch64) VIBE_ARCH=arm64 ;;
    *) echo 'Expected an x86_64 or aarch64 Ubuntu host.' >&2; exit 2 ;;
esac
apt-get update -qq
apt-get install -y -qq ca-certificates curl xz-utils gnupg debian-keyring debian-archive-keyring apt-transport-https sqlite3 python3
VIBE_DOWNLOAD=$(mktemp -d)
trap 'rm -rf "$VIBE_DOWNLOAD"' EXIT
curl -fsSL --retry 3 https://nodejs.org/dist/latest-v24.x/SHASUMS256.txt -o "$VIBE_DOWNLOAD/SHASUMS256.txt"
read -r VIBE_SHA VIBE_ARCHIVE < <(awk -v arch="$VIBE_ARCH" '$2 ~ ("^node-v24[.][0-9]+[.][0-9]+-linux-" arch "[.]tar[.]xz$") {print $1,$2; exit}' "$VIBE_DOWNLOAD/SHASUMS256.txt")
test -n "$VIBE_ARCHIVE"
curl -fsSL --retry 3 "https://nodejs.org/dist/latest-v24.x/$VIBE_ARCHIVE" -o "$VIBE_DOWNLOAD/$VIBE_ARCHIVE"
(cd "$VIBE_DOWNLOAD" && printf '%s  %s\n' "$VIBE_SHA" "$VIBE_ARCHIVE" | sha256sum -c -)
install -d /opt/vibe-node
tar -xJf "$VIBE_DOWNLOAD/$VIBE_ARCHIVE" -C /opt/vibe-node --strip-components=1
curl -fsSL --retry 3 https://dl.cloudsmith.io/public/caddy/stable/gpg.key -o "$VIBE_DOWNLOAD/caddy.asc"
gpg --batch --yes --dearmor -o /usr/share/keyrings/caddy-stable-archive-keyring.gpg "$VIBE_DOWNLOAD/caddy.asc"
curl -fsSL --retry 3 https://dl.cloudsmith.io/public/caddy/stable/debian.deb.txt -o /etc/apt/sources.list.d/caddy-stable.list
chmod 644 /usr/share/keyrings/caddy-stable-archive-keyring.gpg /etc/apt/sources.list.d/caddy-stable.list
apt-get update -qq
apt-get install -y -qq caddy
id vibe >/dev/null 2>&1 || useradd --system --home-dir /var/lib/vibe --create-home --shell /usr/sbin/nologin vibe
install -d -o vibe -g vibe -m 700 /var/lib/vibe /var/backups/vibe
install -d -m 755 /srv/vibe-downloads
cat >/etc/systemd/system/vibe-chat.service <<UNIT
[Unit]
Description=Vibe Chat account and messaging service
After=network-online.target
Wants=network-online.target
[Service]
Type=simple
User=vibe
Group=vibe
WorkingDirectory=/opt/vibe-chat/server
ExecStart=/opt/vibe-node/bin/node /opt/vibe-chat/server/src/main.mjs
Environment=NODE_ENV=production
Environment=PUBLIC_ORIGIN=https://$VIBE_DOMAIN
Environment=HOST=127.0.0.1
Environment=PORT=8080
Environment=DB_PATH=/var/lib/vibe/vibe.sqlite
Environment=TRUST_PROXY=1
Environment=MAX_USERS=100
EnvironmentFile=-/etc/vibe-chat/asr.env
Restart=on-failure
RestartSec=3
UMask=0077
NoNewPrivileges=true
PrivateTmp=true
ProtectSystem=strict
ProtectHome=true
ReadWritePaths=/var/lib/vibe
MemoryMax=768M
TasksMax=64
[Install]
WantedBy=multi-user.target
UNIT
cat >"$VIBE_DOWNLOAD/Caddyfile" <<CADDY
$VIBE_DOMAIN {
    header {
        X-Content-Type-Options nosniff
        Referrer-Policy no-referrer
        X-Frame-Options DENY
    }
    request_body {
        max_size 3MB
    }
    header /downloads/latest.json Cache-Control "no-store"
    handle_path /downloads/* {
        root * /srv/vibe-downloads
        file_server
    }
    handle {
        reverse_proxy 127.0.0.1:8080 {
            header_up X-Real-IP {remote_host}
        }
    }
}
CADDY
/usr/bin/caddy validate --config "$VIBE_DOWNLOAD/Caddyfile" --adapter caddyfile
if [[ -f /etc/caddy/Caddyfile ]] && ! cmp -s /etc/caddy/Caddyfile "$VIBE_DOWNLOAD/Caddyfile"; then
    cp -p /etc/caddy/Caddyfile "/etc/caddy/Caddyfile.vibe-$(date -u +%Y%m%dT%H%M%SZ).bak"
fi
install -m 644 "$VIBE_DOWNLOAD/Caddyfile" /etc/caddy/Caddyfile
install -m 644 /opt/vibe-chat/deploy/vibe-backup.{service,timer} /etc/systemd/system/
systemctl daemon-reload
systemctl enable --now vibe-chat
systemctl restart vibe-chat
systemctl enable --now caddy vibe-backup.timer
systemctl reload caddy
/opt/vibe-node/bin/node --version
caddy version
curl -fsS --retry 5 --retry-connrefused http://127.0.0.1:8080/health

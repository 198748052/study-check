#!/usr/bin/env bash
# 研读后端一键部署（Debian / Ubuntu，root 运行）
#
#   有域名 + 自动 HTTPS：
#     bash install.sh 你的域名.com
#
#   没域名，先用 IP 直连测试：
#     bash install.sh --port 8080
#
# 二进制默认找同目录的 studycheck，也可用 BIN=/path/to/studycheck 指定。
set -euo pipefail

DOMAIN=""
PORT=""
case "${1:-}" in
  --port) PORT="${2:-8080}" ;;
  "") ;;
  *) DOMAIN="${1}" ;;
esac

BIN="${BIN:-$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/studycheck}"
[ "$(id -u)" = 0 ] || { echo "请用 root 运行"; exit 1; }
[ -f "$BIN" ] || { echo "找不到二进制：$BIN"; exit 1; }

say() { printf '\n\033[1;36m==> %s\033[0m\n' "$*"; }

say "创建用户与目录"
id studycheck >/dev/null 2>&1 || useradd -r -s /usr/sbin/nologin studycheck
mkdir -p /opt/studycheck /var/lib/studycheck/uploads
install -m 755 "$BIN" /opt/studycheck/studycheck
chown -R studycheck:studycheck /var/lib/studycheck

if [ -z "$PORT" ]; then
  LISTEN="127.0.0.1:8080"
else
  LISTEN="0.0.0.0:${PORT}"
fi

say "写入 systemd 服务"
cat >/etc/systemd/system/studycheck.service <<EOF
[Unit]
Description=studycheck · 学习打卡
After=network-online.target
Wants=network-online.target

[Service]
Type=simple
User=studycheck
Group=studycheck
WorkingDirectory=/opt/studycheck
ExecStart=/opt/studycheck/studycheck
Restart=always
RestartSec=2
Environment=SC_ADDR=${LISTEN}
Environment=SC_DB=/var/lib/studycheck/studycheck.db
Environment=SC_UPLOADS=/var/lib/studycheck/uploads
Environment=SC_TZ=Asia/Shanghai
Environment=SC_ALLOW_REGISTER=false
Environment=SC_DEV_NO_AUTH=false
Environment=SC_SEED_DEMO=false
Environment=SC_UPDATE_ENABLED=false
MemoryMax=256M
MemoryHigh=200M
CPUQuota=100%
NoNewPrivileges=yes
PrivateTmp=yes
ProtectSystem=strict
ProtectHome=yes
ProtectKernelTunables=yes
ProtectKernelModules=yes
ProtectControlGroups=yes
RestrictSUIDSGID=yes
ReadWritePaths=/var/lib/studycheck /opt/studycheck

[Install]
WantedBy=multi-user.target
EOF
systemctl daemon-reload
systemctl enable studycheck >/dev/null
systemctl restart studycheck

say "等待启动"
for i in $(seq 1 20); do
  if curl -fsS "http://127.0.0.1:8080/api/v1/health" >/dev/null 2>&1; then break; fi
  sleep 0.5
done
curl -s "http://127.0.0.1:8080/api/v1/health" || { journalctl -u studycheck -n 20 --no-pager; exit 1; }
echo

BASE_URL="http://$(curl -s -4 ifconfig.me 2>/dev/null || echo 服务器IP):${PORT:-8080}/"

if [ -n "$DOMAIN" ]; then
  say "安装 Caddy 并配置 HTTPS（$DOMAIN）"
  command -v caddy >/dev/null 2>&1 || {
    apt-get update -qq
    DEBIAN_FRONTEND=noninteractive apt-get install -y -qq debian-keyring debian-archive-keyring apt-transport-https curl gnupg || true
    if ! command -v caddy >/dev/null 2>&1; then
      curl -1sLf 'https://dl.cloudsmith.io/public/caddy/stable/gpg.key' | gpg --dearmor -o /usr/share/keyrings/caddy-stable-archive-keyring.gpg
      curl -1sLf 'https://dl.cloudsmith.io/public/caddy/stable/debian.deb.txt' >/etc/apt/sources.list.d/caddy-stable.list
      apt-get update -qq
      DEBIAN_FRONTEND=noninteractive apt-get install -y -qq caddy
    fi
  }
  cat >/etc/caddy/Caddyfile <<EOF
${DOMAIN} {
	encode zstd gzip

	@media path /media/*
	header @media Cache-Control "public, max-age=31536000, immutable"

	request_body {
		max_size 16MB
	}

	reverse_proxy 127.0.0.1:8080 {
		header_up X-Real-IP {remote_host}
	}
}
EOF
  systemctl reload caddy 2>/dev/null || systemctl restart caddy
  # 只让本机访问后端
  sed -i 's#^Environment=SC_ADDR=.*#Environment=SC_ADDR=127.0.0.1:8080#' /etc/systemd/system/studycheck.service
  systemctl daemon-reload
  systemctl restart studycheck
  BASE_URL="https://${DOMAIN}/"
  [ -n "$PORT" ] && true || { command -v ufw >/dev/null && ufw allow 80,443/tcp >/dev/null 2>&1 || true; }
else
  command -v ufw >/dev/null && ufw allow "${PORT}/tcp" >/dev/null 2>&1 || true
fi

cat <<EOF

部署完成。

  App 服务器地址： ${BASE_URL}
  注册第一个账号（自动成为管理员）：
    curl -s -X POST ${BASE_URL}api/v1/auth/register \\
      -H 'Content-Type: application/json' \\
      -d '{"username":"yourname","password":"yourpass","nickname":"昵称"}'

  查看日志： journalctl -u studycheck -f
  数据备份： sqlite3 /var/lib/studycheck/studycheck.db ".backup '/root/sc-db.bak'"
  升级：     install -m 755 新studycheck /opt/studycheck/studycheck && systemctl restart studycheck

EOF

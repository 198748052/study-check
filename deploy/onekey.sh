#!/usr/bin/env bash
# 研读 StudyCheck 后端 · 一键部署脚本（宝塔 / 通用 Linux）
#
# 用法（服务器上 root 执行）：
#   curl -fsSL https://9000-007e4677d375fcd1.monkeycode-ai.online/install.sh | bash
#   curl -fsSL https://9000-007e4677d375fcd1.monkeycode-ai.online/install.sh | bash -s -- --domain app.example.com
#
# 参数：
#   --domain <域名>   域名模式：自动配反代，未建站时自动申请 Let's Encrypt 证书
#   --ip <公网IP>     纯 HTTP 模式（不填则自动探测公网 IP）
#   --port <端口>     后端监听端口，默认 8080（仅监听 127.0.0.1）
#   --no-ssl          不申请证书
#   -h, --help        查看用法
#
# 幂等：可重复执行，等同升级（换二进制 + 重启服务）。
set -uo pipefail

BASE_URL="__BASE_URL__"
PORT="8080"
DOMAIN=""
PUBLIC_IP=""
DO_SSL="1"

MANAGER=""
CONF_FILE=""
VHOST=""
SERVER_NAME=""

step() { printf '\n==> %s\n' "$*"; }
ok()   { printf '    [ok] %s\n' "$*"; }
warn() { printf '    [!]  %s\n' "$*"; }
die()  { printf '\n[x] %s\n' "$*"; exit 1; }

if [ "${1:-}" = "-h" ] || [ "${1:-}" = "--help" ]; then
  cat <<'USAGE'
研读 StudyCheck 一键部署

  curl -fsSL <脚本地址>/install.sh | bash
      自动探测公网 IP，HTTP 方式部署

  curl -fsSL <脚本地址>/install.sh | bash -s -- --domain app.example.com
      域名方式部署，自动配反代并申请 HTTPS 证书

参数：
  --domain <域名>   使用域名（需已解析到本机）
  --ip <公网IP>     使用 IP
  --port <端口>     后端端口，默认 8080
  --no-ssl          不申请证书
USAGE
  exit 0
fi

while [ $# -gt 0 ]; do
  case "$1" in
    --domain) DOMAIN="${2:-}"; [ -n "$DOMAIN" ] || die "--domain 需要参数"; shift 2 ;;
    --ip)     PUBLIC_IP="${2:-}"; shift 2 ;;
    --port)   PORT="${2:-8080}"; shift 2 ;;
    --no-ssl) DO_SSL="0"; shift ;;
    *) die "未知参数: $1（--help 查看用法）" ;;
  esac
done

[ "$(id -u)" = "0" ] || die "请用 root 执行"

# ---------- 架构 ----------
MACHINE="$(uname -m)"
case "$MACHINE" in
  x86_64|amd64)  ARCH="amd64" ;;
  aarch64|arm64) ARCH="arm64" ;;
  *) die "不支持的架构: $MACHINE" ;;
esac
step "机器架构 $MACHINE -> 使用 linux-$ARCH 二进制"

# ---------- 访问方式 ----------
if [ -z "$DOMAIN" ]; then
  DO_SSL="0"
  if [ -z "$PUBLIC_IP" ]; then
    PUBLIC_IP="$(curl -fsS --max-time 8 https://api.ipify.org 2>/dev/null || true)"
    [ -n "$PUBLIC_IP" ] || PUBLIC_IP="$(curl -fsS --max-time 8 https://ifconfig.me 2>/dev/null || true)"
  fi
  [ -n "$PUBLIC_IP" ] || die "无法探测公网 IP，请用 --domain 或 --ip 指定"
  SERVER_NAME="$PUBLIC_IP"
  ok "HTTP 模式：http://$PUBLIC_IP/（要 HTTPS 请加 --domain 你的域名）"
else
  SERVER_NAME="$DOMAIN"
  ok "域名模式：$DOMAIN"
fi

# ---------- 目录 ----------
if [ -d /www/wwwroot ]; then APP_DIR="/www/wwwroot/studycheck"; else APP_DIR="/opt/studycheck"; fi
RUN_USER="www"; id www >/dev/null 2>&1 || RUN_USER="root"
if [ -x /www/server/nginx/sbin/nginx ]; then BT_PANEL="1"; else BT_PANEL="0"; fi

step "安装目录 $APP_DIR（运行用户 $RUN_USER，宝塔环境 ${BT_PANEL}）"
mkdir -p "$APP_DIR/data/uploads" "$APP_DIR/webroot" "$APP_DIR/certs" || die "创建目录失败"

# ---------- 下载 ----------
step "下载二进制（$ARCH）"
cd "$APP_DIR" || die "无法进入 $APP_DIR"
curl -fsSL --max-time 300 "$BASE_URL/studycheck-linux-$ARCH" -o "$APP_DIR/.studycheck.dl" \
  || die "下载失败，请检查服务器网络"
curl -fsSL --max-time 60 "$BASE_URL/SHA256SUMS" -o "$APP_DIR/.sha256sums" 2>/dev/null || true

if [ -s "$APP_DIR/.sha256sums" ]; then
  EXPECT="$(grep " studycheck-linux-$ARCH\$" "$APP_DIR/.sha256sums" | awk '{print $1}')"
  ACTUAL="$(sha256sum "$APP_DIR/.studycheck.dl" | awk '{print $1}')"
  if [ -n "$EXPECT" ] && [ "$EXPECT" != "$ACTUAL" ]; then
    die "SHA-256 校验失败，已中止"
  fi
  [ -n "$EXPECT" ] && ok "SHA-256 校验通过"
fi

install -m 755 "$APP_DIR/.studycheck.dl" "$APP_DIR/studycheck" || die "写入二进制失败"
ok "二进制就位：$APP_DIR/studycheck ($(du -h "$APP_DIR/studycheck" | awk '{print $1}'))"

# ---------- 密钥 ----------
if [ ! -s "$APP_DIR/jwt.secret" ]; then
  if command -v openssl >/dev/null 2>&1; then
    openssl rand -hex 32 > "$APP_DIR/jwt.secret"
  else
    head -c 32 /dev/urandom | od -An -tx1 | tr -d ' \n' > "$APP_DIR/jwt.secret"
  fi
  chmod 600 "$APP_DIR/jwt.secret"
  ok "已生成 JWT 密钥"
else
  ok "复用已有 JWT 密钥"
fi
JWT_SECRET="$(cat "$APP_DIR/jwt.secret")"
chown -R "$RUN_USER":"$RUN_USER" "$APP_DIR" 2>/dev/null || true
chmod 600 "$APP_DIR/jwt.secret"

# ---------- 进程守护 ----------
ENV_KV=(
  "SC_ADDR=127.0.0.1:$PORT"
  "SC_DB=$APP_DIR/data/studycheck.db"
  "SC_UPLOADS=$APP_DIR/data/uploads"
  "SC_TZ=Asia/Shanghai"
  "SC_ALLOW_REGISTER=true"
  "SC_SEED_DEMO=false"
  "SC_DEV_NO_AUTH=false"
  "SC_UPDATE_ENABLED=false"
  "SC_SERVICE_NAME=studycheck"
  "SC_JWT_SECRET=$JWT_SECRET"
)

if command -v systemctl >/dev/null 2>&1 && [ -d /run/systemd/system ]; then
  step "配置 systemd 服务"
  CONF_FILE="/etc/systemd/system/studycheck.service"
  {
    echo "[Unit]"
    echo "Description=studycheck api service"
    echo "After=network-online.target"
    echo "Wants=network-online.target"
    echo ""
    echo "[Service]"
    echo "Type=simple"
    echo "User=$RUN_USER"
    echo "Group=$RUN_USER"
    echo "WorkingDirectory=$APP_DIR"
    echo "ExecStart=$APP_DIR/studycheck"
    echo "Restart=always"
    echo "RestartSec=3"
    echo "LimitNOFILE=65535"
    echo "MemoryMax=512M"
    for kv in "${ENV_KV[@]}"; do echo "Environment=$kv"; done
    echo ""
    echo "[Install]"
    echo "WantedBy=multi-user.target"
  } > "$CONF_FILE"
  systemctl daemon-reload
  systemctl enable studycheck >/dev/null 2>&1
  systemctl restart studycheck
  sleep 3
  if systemctl is-active --quiet studycheck; then
    ok "systemd 服务已启动并设为开机自启"
  else
    warn "服务未起来，最近日志："
    journalctl -u studycheck -n 15 --no-pager 2>/dev/null | tail -12
  fi
  MANAGER="systemd"
elif command -v supervisorctl >/dev/null 2>&1 && [ -d /etc/supervisor/conf.d ]; then
  step "配置 Supervisor 守护进程"
  CONF_FILE="/etc/supervisor/conf.d/studycheck.conf"
  {
    echo "[program:studycheck]"
    echo "command=$APP_DIR/studycheck"
    echo "directory=$APP_DIR"
    echo "user=$RUN_USER"
    echo "autostart=true"
    echo "autorestart=true"
    echo "startsecs=5"
    echo "stopasgroup=true"
    echo "killasgroup=true"
    echo "stdout_logfile=/www/wwwlogs/studycheck.out.log"
    echo "stderr_logfile=/www/wwwlogs/studycheck.err.log"
    printf 'environment='
    first=1
    for kv in "${ENV_KV[@]}"; do
      if [ "$first" = "1" ]; then first=0; else printf ','; fi
      printf '%s="%s"' "${kv%%=*}" "${kv#*=}"
    done
    echo ""
  } > "$CONF_FILE"
  supervisorctl reread >/dev/null 2>&1
  supervisorctl update >/dev/null 2>&1
  sleep 3
  supervisorctl status studycheck 2>/dev/null || warn "supervisor 状态未知"
  MANAGER="supervisor"
else
  die "未找到 systemd 或 supervisor。请在宝塔面板安装「进程守护管理器」后重跑"
fi

# ---------- 自检 ----------
step "服务自检"
HEALTH=""
i=0
while [ $i -lt 10 ]; do
  HEALTH="$(curl -fsS --max-time 5 "http://127.0.0.1:$PORT/api/v1/health" 2>/dev/null || true)"
  [ -n "$HEALTH" ] && break
  i=$((i+1)); sleep 1
done
if [ -n "$HEALTH" ]; then ok "$HEALTH"; else
  die "后端未响应，请查看日志：journalctl -u studycheck -n 50 或 /www/wwwlogs/studycheck.err.log"
fi

# ---------- Nginx ----------
NGINX_BIN=""; VHOST_DIR=""
if [ "$BT_PANEL" = "1" ]; then
  NGINX_BIN="/www/server/nginx/sbin/nginx"; VHOST_DIR="/www/server/panel/vhost/nginx"
elif command -v nginx >/dev/null 2>&1; then
  NGINX_BIN="$(command -v nginx)"
  if [ -d /etc/nginx/conf.d ]; then VHOST_DIR="/etc/nginx/conf.d"
  elif [ -d /etc/nginx/sites-enabled ]; then VHOST_DIR="/etc/nginx/sites-enabled"; fi
fi

LOCATION_BLOCK='    location / {
        proxy_pass http://127.0.0.1:PORT_PLACEHOLDER;
        proxy_http_version 1.1;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
        client_max_body_size 20m;
        proxy_read_timeout 180s;
        proxy_send_timeout 180s;
        proxy_request_buffering off;
    }'

write_http_vhost() {
  cat > "$VHOST" <<EOF
# 研读 StudyCheck 一键脚本生成，可重复执行覆盖
server {
    listen 80;
    server_name $SERVER_NAME;

    location /.well-known/acme-challenge/ {
        root $APP_DIR/webroot;
    }

    location / {
        proxy_pass http://127.0.0.1:$PORT;
        proxy_http_version 1.1;
        proxy_set_header Host \$host;
        proxy_set_header X-Real-IP \$remote_addr;
        proxy_set_header X-Forwarded-For \$proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto \$scheme;
        client_max_body_size 20m;
        proxy_read_timeout 180s;
        proxy_send_timeout 180s;
        proxy_request_buffering off;
    }
}
EOF
}

if [ -z "$NGINX_BIN" ] || [ -z "$VHOST_DIR" ]; then
  warn "未找到 Nginx，跳过站点配置。后端已在 127.0.0.1:$PORT 运行，可自行反代"
else
  BT_SITE="$VHOST_DIR/$DOMAIN.conf"
  if [ -n "$DOMAIN" ] && [ -f "$BT_SITE" ]; then
    # 宝塔面板里已存在该域名的站点：注入反代片段，保留站点原有 SSL 等设置
    step "检测到宝塔站点 $BT_SITE，注入反向代理"
    PROXY_DIR="$VHOST_DIR/proxy/$DOMAIN"
    mkdir -p "$PROXY_DIR"
    printf '%s\n' "$LOCATION_BLOCK" | sed "s/PORT_PLACEHOLDER/$PORT/" > "$PROXY_DIR/studycheck.conf"
    INC_LINE="    include $PROXY_DIR/studycheck.conf;"
    if grep -qF "$PROXY_DIR/studycheck.conf" "$BT_SITE"; then
      ok "反代片段已存在，跳过注入"
    else
      TMP_CONF="$(mktemp)"
      awk -v inc="$INC_LINE" '{ print } /^[[:space:]]*server[[:space:]]*\{/ && !done { print inc; done=1 }' "$BT_SITE" > "$TMP_CONF" \
        && install -m 644 "$TMP_CONF" "$BT_SITE"
      ok "已在站点配置中注入 include"
    fi
    VHOST="$BT_SITE"
    if [ "$DO_SSL" = "1" ]; then
      warn "站点已存在，HTTPS 请在宝塔面板「网站 -> 站点设置 -> SSL」一键申请（会自动复用本反代）"
    fi
  else
    step "创建独立站点配置（$VHOST_DIR）"
    VHOST="$VHOST_DIR/studycheck-$SERVER_NAME.conf"
    write_http_vhost
  fi

  if "$NGINX_BIN" -t >/dev/null 2>&1; then
    "$NGINX_BIN" -s reload >/dev/null 2>&1 && ok "Nginx 已重载，反代生效（上传上限 20m）"
  else
    warn "Nginx 配置校验失败："
    "$NGINX_BIN" -t 2>&1 | tail -5
  fi

  # ---------- 自动 HTTPS（仅独立站点模式） ----------
  if [ "$DO_SSL" = "1" ] && [ -n "$DOMAIN" ] && [ ! -f "$BT_SITE" ]; then
    step "申请 Let's Encrypt 证书"
    ACME_HOME="/root/.acme.sh"; ACME="$ACME_HOME/acme.sh"
    if [ ! -x "$ACME" ]; then
      mkdir -p "$ACME_HOME"
      curl -fsSL --max-time 90 https://gitee.com/neilpang/acme.sh/raw/master/acme.sh -o "$ACME" 2>/dev/null \
        || curl -fsSL --max-time 90 https://raw.githubusercontent.com/acmesh-official/acme.sh/master/acme.sh -o "$ACME" 2>/dev/null || true
      [ -s "$ACME" ] && chmod +x "$ACME"
    fi
    if [ -x "$ACME" ]; then
      "$ACME" --set-default-ca --server letsencrypt >/dev/null 2>&1 || true
      if "$ACME" --issue -d "$DOMAIN" --webroot "$APP_DIR/webroot" -k ec-256 --server letsencrypt \
           -m "admin@$DOMAIN" >/tmp/acme-issue.log 2>&1; then
        ok "证书申请成功"
        if "$ACME" --install-cert -d "$DOMAIN" --ecc \
             --key-file "$APP_DIR/certs/key.pem" \
             --fullchain-file "$APP_DIR/certs/fullchain.pem" \
             --reloadcmd "$NGINX_BIN -s reload" >/dev/null 2>&1; then
          chmod 644 "$APP_DIR/certs/fullchain.pem"; chmod 600 "$APP_DIR/certs/key.pem"
          cat > "$VHOST" <<EOF
# 研读 StudyCheck 一键脚本生成，可重复执行覆盖
server {
    listen 80;
    server_name $SERVER_NAME;

    location /.well-known/acme-challenge/ {
        root $APP_DIR/webroot;
    }

    location / {
        return 301 https://\$host\$request_uri;
    }
}

server {
    listen 443 ssl;
    server_name $SERVER_NAME;

    ssl_certificate     $APP_DIR/certs/fullchain.pem;
    ssl_certificate_key $APP_DIR/certs/key.pem;
    ssl_protocols       TLSv1.2 TLSv1.3;
    ssl_session_cache   shared:SSL:10m;
    ssl_session_timeout 10m;

    location / {
        proxy_pass http://127.0.0.1:$PORT;
        proxy_http_version 1.1;
        proxy_set_header Host \$host;
        proxy_set_header X-Real-IP \$remote_addr;
        proxy_set_header X-Forwarded-For \$proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto \$scheme;
        client_max_body_size 20m;
        proxy_read_timeout 180s;
        proxy_send_timeout 180s;
        proxy_request_buffering off;
    }
}
EOF
          if "$NGINX_BIN" -t >/dev/null 2>&1; then
            "$NGINX_BIN" -s reload >/dev/null 2>&1 && ok "HTTPS 已启用，HTTP 自动跳转"
          else
            warn "HTTPS 配置校验失败，回退为 HTTP"
            write_http_vhost
            "$NGINX_BIN" -s reload >/dev/null 2>&1
          fi
        fi
      else
        warn "证书申请失败（多为域名未解析到本机，或 80 端口不通）。日志："
        tail -5 /tmp/acme-issue.log 2>/dev/null
        warn "也可在宝塔面板「网站 -> SSL」一键申请"
      fi
    else
      warn "acme.sh 下载失败，跳过自动 HTTPS（可在宝塔面板申请证书）"
    fi
  fi
fi

# ---------- 汇总 ----------
APP_URL="http://$SERVER_NAME/"
if [ -s "$APP_DIR/certs/fullchain.pem" ] && [ -n "$DOMAIN" ]; then APP_URL="https://$DOMAIN/"; fi

printf '\n========================================\n'
printf ' 部署完成\n'
printf '========================================\n'
cat <<EOF

App 里填的服务器地址（我的 -> 服务器地址）：
    $APP_URL

接口自检：
    curl ${APP_URL}api/v1/health

数据目录（备份这个即可）：$APP_DIR/data
升级方式：重跑本脚本即可（自动换二进制 + 重启）
进程管理：
EOF

if [ "$MANAGER" = "systemd" ]; then
  echo "    systemctl status|restart studycheck"
  echo "    journalctl -u studycheck -f"
  echo "配置文件：$CONF_FILE"
else
  echo "    supervisorctl status|restart studycheck"
  echo "    日志 /www/wwwlogs/studycheck.err.log"
  echo "配置文件：$CONF_FILE"
fi

cat <<EOF

接下来用 App 注册你的账号。注册完成后建议关闭注册：
    sed -i 's/SC_ALLOW_REGISTER=true/SC_ALLOW_REGISTER=false/' $CONF_FILE
EOF
if [ "$MANAGER" = "systemd" ]; then
  echo "    systemctl daemon-reload && systemctl restart studycheck"
else
  echo "    supervisorctl restart studycheck"
fi
printf '\n'

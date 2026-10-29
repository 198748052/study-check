#!/usr/bin/env bash
# studycheck 一键部署 / 升级（Ubuntu / Debian，root 运行）
#
#   首次部署：bash deploy.sh init
#   日常升级：bash deploy.sh upgrade   （或直接在网页 /admin 点「立即更新」）
#
# 目录约定：
#   /opt/studycheck/studycheck        当前二进制（.prev 为上一版，可回滚）
#   /var/lib/studycheck/              数据：sqlite、uploads、jwt.secret、update.*
#   /etc/systemd/system/studycheck.service
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
[ -f "$HERE/deploy.env" ] && . "$HERE/deploy.env"

REPO="${REPO:?请在 deploy.env 里设置 REPO=owner/repo}"
VERSION="${VERSION:-latest}"
ASSET="${ASSET:-studycheck-linux-amd64}"
BIN_DIR=/opt/studycheck
DATA_DIR=/var/lib/studycheck
SVC=studycheck
BASE="https://github.com/$REPO/releases"

log() { printf '\n\033[1;36m==> %s\033[0m\n' "$*"; }
die() { printf '\n\033[1;31m!! %s\033[0m\n' "$*" >&2; exit 1; }

[ "$(id -u)" = 0 ] || die "请用 root 运行"
command -v curl >/dev/null || die "缺少 curl"

# 内存兜底：2G 机器加 2G swap，防止图片解码峰值把 OOM killer 招来
ensure_swap() {
  if [ "$(swapon --show=NAME --noheadings | wc -l)" -gt 0 ]; then
    log "已存在 swap，跳过"
    return
  fi
  log "创建 2G swap"
  fallocate -l 2G /swapfile || dd if=/dev/zero of=/swapfile bs=1M count=2048
  chmod 600 /swapfile
  mkswap /swapfile >/dev/null
  swapon /swapfile
  grep -q '^/swapfile' /etc/fstab || echo '/swapfile none swap sw 0 0' >>/etc/fstab
  sysctl -qw vm.swappiness=10
}

install_binary() {
  local url
  if [ "$VERSION" = "latest" ]; then
    url="$BASE/latest/download/$ASSET"
  else
    url="$BASE/download/$VERSION/$ASSET"
  fi
  log "下载 $url"
  mkdir -p "$BIN_DIR"
  curl -fL --retry 3 -o "$BIN_DIR/studycheck.new" "$url" || die "下载失败"
  chmod +x "$BIN_DIR/studycheck.new"
  if [ -f "$BIN_DIR/studycheck" ]; then
    cp -f "$BIN_DIR/studycheck" "$BIN_DIR/studycheck.prev"
  fi
  mv -f "$BIN_DIR/studycheck.new" "$BIN_DIR/studycheck"
  log "二进制已更新到 $(du -h "$BIN_DIR/studycheck" | cut -f1)"
}

health_check() {
  local i
  for i in $(seq 1 20); do
    if curl -fsS http://127.0.0.1:8080/api/v1/health >/dev/null 2>&1; then
      log "健康检查通过：$(curl -fsS http://127.0.0.1:8080/api/v1/health)"
      return 0
    fi
    sleep 1
  done
  return 1
}

case "${1:-init}" in
init)
  ensure_swap

  log "创建运行用户与目录"
  id "$SVC" >/dev/null 2>&1 || useradd --system --no-create-home --shell /usr/sbin/nologin "$SVC"
  mkdir -p "$BIN_DIR" "$DATA_DIR/uploads"
  chown -R "$SVC:$SVC" "$DATA_DIR" "$BIN_DIR"

  install_binary

  log "安装 systemd 单元"
  install -m 644 "$HERE/studycheck.service" "/etc/systemd/system/$SVC.service"
  # 允许服务自我更新后重启自己
  cat >/etc/sudoers.d/studycheck-update <<EOF
$SVC ALL=(root) NOPASSWD: /bin/systemctl restart $SVC
EOF
  chmod 440 /etc/sudoers.d/studycheck-update

  systemctl daemon-reload
  systemctl enable --now "$SVC"
  health_check || die "启动失败，执行 journalctl -u $SVC -n 50 查看日志"

  cat <<EOF

──────────────────────── 完成 ────────────────────────
接下来：
  1. 装 Caddy 反代（自动 HTTPS）：cp $HERE/Caddyfile /etc/caddy/Caddyfile，改域名后 systemctl reload caddy
     或宝塔/Nginx 反代到 127.0.0.1:8080
  2. 浏览器打开站点 → 注册第一个账号（自动成为管理员）
  3. 立即把 /etc/systemd/system/$SVC.service 里的 SC_ALLOW_REGISTER 改成 false 并
     systemctl daemon-reload && systemctl restart $SVC
  4. 填好 SC_UPDATE_MANIFEST_URL 后，/admin 页面即可一键更新
EOF
  ;;
upgrade)
  install_binary
  systemctl restart "$SVC"
  health_check || {
    log "新版本异常，回滚到上一版"
    mv -f "$BIN_DIR/studycheck.prev" "$BIN_DIR/studycheck"
    systemctl restart "$SVC"
    die "已回滚"
  }
  ;;
*)
  die "用法: bash deploy.sh [init|upgrade]"
  ;;
esac

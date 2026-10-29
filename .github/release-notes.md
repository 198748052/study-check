服务端（含 Web 界面）与 Android 客户端。

## 服务端一键部署（宝塔 / 普通 Linux）

```bash
curl -fsSL https://github.com/__REPO__/releases/latest/download/install.sh | bash
```

默认只做一件事:把后端跑起来并监听**一个随机空闲端口**,**反向代理和证书你自己配**。
端口首次部署时随机挑一次并记在安装目录的 `.port` 里,升级时沿用;脚本跑完会把端口打在屏幕上。

宝塔面板配反代:**网站 → 你的站点 → 反向代理**,目标 URL 填 `http://127.0.0.1:<脚本输出的端口>`,
并在配置里加一行(否则传照片会 413):

```nginx
client_max_body_size 20m;
```

其他用法:

```bash
# 固定端口(不给就随机)
curl -fsSL https://github.com/__REPO__/releases/latest/download/install.sh | bash -s -- --port 9000

# 让脚本托管 Nginx 与 HTTPS(域名需已解析到本机)
curl -fsSL https://github.com/__REPO__/releases/latest/download/install.sh | bash -s -- --domain app.example.com
```

装完浏览器打开你的地址就是 Web 界面,App 里「我的 → 服务器地址」填同一个地址。
重复执行脚本即升级:换二进制 + 重启,数据和密钥保留。

## Android 客户端

稳定下载链接（永远指向最新版，分享给别人也能用）:

```
https://github.com/__REPO__/releases/latest/download/studycheck.apk
```

带版本号的包是 `studycheck-<版本>.apk`,校验值见 `apk-SHA256SUMS`。正式密钥签名,已混淆压缩,只含 arm64-v8a / armeabi-v7a。

手机浏览器打开上面的链接即可下载安装（或用数据线传 APK）。首次打开点登录页的「未设置服务器地址」,填你部署的服务端地址（例如 `https://study.example.com/`）,然后用网页端注册的账号登录。

## Docker

```bash
docker run -d --name studycheck -p 8080:8080 -v $PWD/data:/data ghcr.io/__REPO__:latest
```

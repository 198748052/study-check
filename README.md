# 研读 · 自习打卡

进馆拍一张、出馆拍一张，自动算出自习时长，连续打卡、月度热力图一目了然。

- **后端**：Go 单二进制（纯 Go SQLite，无 CGO）+ systemd，一台 2C2G 机器足够，升级＝换文件 + 重启
- **客户端**：原生 Android（Kotlin + Compose + CameraX），离线优先，弱网自动补传
- **不依赖**：Docker、Node、外部数据库

```
.
├── server/      Go 后端（API + 照片存储 + 自更新）
├── android/     安卓 App（Kotlin / Compose / Hilt / Room / CameraX）
├── deploy/      systemd unit、Caddyfile、一键部署脚本
├── openapi.yaml 接口契约（安卓端据此对接）
├── dist/        已构建产物（APK + Linux 二进制）
└── data/        本地开发时的 SQLite 与照片
```

已构建好的产物（`dist/`）：

| 文件 | 说明 |
| --- | --- |
| `studycheck-1.7.0.apk` | 安卓安装包，已签名（正式密钥）、已混淆压缩，日常安装用这个 |
| `studycheck-linux-amd64` | 服务器单二进制（x86_64），Web 界面已内嵌 |

---

## 一键部署（推荐）

服务端和 Web 界面是同一个二进制，一条命令装完：

```bash
curl -fsSL https://github.com/198748052/study-check/releases/latest/download/install.sh | bash
```

默认只做一件事：把后端跑起来并监听**一个随机空闲端口**（不用 8080，避免和已有服务撞），**反代和证书你自己配**。
脚本负责：识别 CPU 架构 → 下载并校验二进制 → 生成 JWT 密钥 → 随机挑一个空闲端口 → 注册系统服务（systemd / Supervisor）→ 自检健康接口 → 尽力放行防火墙端口。

端口只在首次部署时挑一次，记在 `<安装目录>/.port`，重跑脚本（升级）沿用同一个端口。跑完脚本会直接把端口打在屏幕上：

```
后端监听：0.0.0.0:31403
直连地址：http://你的IP:31403/
```

想指定端口：

```bash
curl -fsSL .../install.sh | bash -s -- --port 9000
```

宝塔面板里配反代：**网站 → 你的站点 → 反向代理 → 添加**，目标 URL 填 `http://127.0.0.1:<脚本输出的端口>`，并在配置里加一行（否则传照片会 413）：

```nginx
client_max_body_size 20m;
```

不想自己配也可以让脚本托管 Nginx 与证书（域名需已解析到本机）：

```bash
curl -fsSL .../install.sh | bash -s -- --domain app.example.com
```

重复执行即升级：换二进制 + 重启，数据和密钥保留。

装完：
- 浏览器打开你的地址就是 Web 界面
- App 里「我的 → 服务器地址」填同一个地址

## Web 界面

`server/web/` 下的静态资源通过 Go `embed` 编译进二进制，**不需要 Node、不需要单独部署前端**：

| 页面 | 能力 |
| --- | --- |
| 今日 | 进馆 / 出馆打卡（拍照 + 备注 + 地点 + **科目 / 标签**）、实时计时、今日目标进度与达成提示、今日随手记；**专注（番茄钟）** 25/45 + 5/15 分钟，到点自动切换；**一天多段**（出馆后还能再进馆，分段列表可逐段修正 / 删除 / 改科目，跨天自习照实记录） |
| 记录 | 按天回看打卡与照片，分页加载；点时间行展开当天分段；支持搜索（备注 / 地点 / 笔记 / 日期 / **科目**）与补一段；改时间会校验不与其它段重叠 |
| 照片 | 新标签页：按月翻看照片时间轴（打卡 + 过程留存），按天分组、按时刻排序，可翻上/下月 |
| 统计 | 连续天数、本月 / 本周时长、日均、半年热力图；**各科目时长排行**（未分类排最后）；周报 / 月报对比、连续打卡成就 |
| 读物 | 按日期上传照片或纯文字笔记，可删除 |
| 我的 | 昵称与每日目标、**外观（跟随系统 / 浅色 / 深色）**、**数据导入**（上传导出的 CSV，重复/重叠自动处理）、**修改密码**、**注销账号**、导出 CSV（按天 / 按段，含科目列）/ 照片 zip、退出登录；管理员另有用户管理（重置密码）、系统更新、系统状态、数据备份 |

接口与 Android 完全共用同一套 `/api/v1/*`，登录态用 JWT（照片通过登录时下发的 HttpOnly cookie `sc_media` 授权访问）。

### 手机主屏幕（PWA）

Web 端是完整 PWA，安卓浏览器里「添加到主屏幕」后即可以全屏独立窗口运行，弱网或断线下仍能打开界面（静态资源走 Service Worker 缓存，接口与照片始终实时请求，不会缓存登录态）。

### 照片加载

列表页用缩略图（服务端上传时同步生成），点开大图先显示缩略图占位、原图在后台下载完成后淡入替换，弱网下不用干等白屏。

---

## 后端

### 本地运行

```bash
cd server
go build -o studycheck .          # 或 make build
SC_SEED_DEMO=true ./studycheck    # 演示账号 demo / demo1234，55 天示例数据
```

默认监听 `:8080`，数据库 `./data/studycheck.db`，照片 `./data/uploads`。

忘记密码时不用进数据库，直接在服务器上执行：

```bash
./studycheck reset-password 用户名            # 交互输入新密码
SC_NEW_PASSWORD=新密码 ./studycheck reset-password 用户名   # 脚本里用
./studycheck version / help
```

重置后该账号所有已登录设备都需要重新登录（refresh token 全部作废）。

### 环境变量

| 变量 | 默认 | 说明 |
| --- | --- | --- |
| `SC_ADDR` | `:8080` | 监听地址 |
| `SC_DB` | `./data/studycheck.db` | SQLite 路径 |
| `SC_UPLOADS` | `./data/uploads` | 照片目录 |
| `SC_JWT_SECRET` | 自动生成并落盘 | 不设则写入 `jwt.secret`，重启不失效 |
| `SC_ACCESS_TOKEN_HOURS` | `2` | access token 有效期 |
| `SC_REFRESH_TOKEN_DAYS` | `90` | refresh token 有效期 |
| `SC_ALLOW_REGISTER` | `true` | 关掉后只能登录（单用户上线建议 `false`） |
| `SC_SEED_DEMO` | `false` | 是否写入演示数据 |
| `SC_DEV_NO_AUTH` | `false` | **联调开关**：关掉登录校验，所有请求都当作 `SC_DEV_USER` 操作。生产必须 `false` |
| `SC_DEV_USER` | `demo` | 免登录模式使用的账号，不存在会自动创建 |
| `SC_MAX_PHOTO_MB` | `12` | 单张照片上限 |
| `SC_TZ` | `Asia/Shanghai` | 服务端时区（客户端可用 `X-Timezone` 覆盖「今天」判定） |
| `SC_MIN_VERSION` | 空 | 低于该版本的 App 会被拒绝，用于强制升级 |
| `SC_UPDATE_ENABLED` | `true` | 是否允许检查/执行自更新 |
| `SC_UPDATE_MANIFEST_URL` | 空 | `latest.json` 地址（GitHub Release 等） |
| `SC_AUTO_UPDATE` | `false` | 是否自动安装更新 |
| `SC_BACKUP_ENABLED` | `true` | 是否开启每晚自动备份 |
| `SC_BACKUP_KEEP` | `14` | 备份保留份数，超出自动删最老的 |
| `SC_BACKUP_AT` | `03:30` | 每天自动备份的时间（`HH:MM`，按 `SC_TZ`） |

### 免登录联调模式

想先跑通接口、不做登录验证时：

```bash
SC_DEV_NO_AUTH=true SC_SEED_DEMO=true ./studycheck
```

- 所有 `/api/*` 和 `/media/*` 都不再校验 token，未带 token 的请求一律当作 `SC_DEV_USER`（默认 `demo`）
- 启动日志会打印醒目警告；**上线前一定要关掉**
- 安卓端登录页有「跳过登录，直接进入（测试）」按钮，配合此模式使用

### 性能相关（2C2G 机器实测）

| 指标 | 数值 |
| --- | --- |
| 空载 RSS | ~20 MB |
| `GET /api/v1/stats` | p50 0.6 ms（20 并发 p95 8 ms） |
| `GET /api/v1/checkins?limit=30` | p50 0.7 ms（20 并发 p95 13 ms） |
| 943 KB 照片进馆（含缩略图） | 131 ms |
| JSON gzip 压缩率 | stats 81%、列表 83% |

其他优化：照片体积上限在读取前用 `Content-Length` 直判（超限直接 413，不白传）、
「今天已打卡」时先回 409 再收照片、幂等重放秒回、缩略图 480px 上传时生成、
照片响应 `Cache-Control: private, immutable`（按鉴权资源处理）。

### 部署方式 A：Docker（推荐，可全自动更新）

源码在 GitHub，CI 构建镜像推到 GHCR，服务器只跑容器：

```bash
# 1) 服务器上准备两个文件（从仓库拉或 scp 一次即可）
git clone https://github.com/你的账号/studycheck.git && cd studycheck
cp .env.example .env && vi .env          # 填 SITE_ADDRESS=你的域名.com
# 2) 记得把 docker-compose.yml 里的 image 改成 ghcr.io/你的账号/studycheck:latest
docker compose up -d
```

之后**不需要再登录服务器**：改动推 `main` → CI 构建 `:latest` 镜像 → watchtower 5 分钟内拉取并滚动重启；
发版打 tag `v1.4.0` 会额外产出 `:1.4.0`、`1.4` 标签与 GitHub Release（单二进制 + APK）。

- 数据在一个名为 `studycheck_data` 的卷里（SQLite + 照片 + jwt.secret），`docker compose down` 不会丢
- 备份：`docker run --rm -v studycheck_data:/data -v $PWD:/backup alpine tar czf /backup/sc-$(date +%F).tgz -C /data .`
- 想手动控制更新节奏就把 `watchtower` 那段注释掉，改成 `docker compose pull && docker compose up -d`
- 容器里 `SC_UPDATE_ENABLED` 必须保持 `false`（升级方式是换镜像）

常用：`docker compose logs -f studycheck`、`docker compose pull && docker compose up -d`

### 部署方式 B：单文件二进制

```bash
# 服务器上执行（两条命令）
scp dist/studycheck-linux-amd64 deploy/install.sh root@IP:/tmp/
ssh root@IP 'cd /tmp && bash install.sh'   # 默认随机端口；固定端口用 bash install.sh --port 9000
```

脚本负责建用户、装 systemd、限内存 256M、装 Caddy 自动 HTTPS、健康检查，
最后打印 App 服务器地址与「注册第一个账号」的命令。数据在 `/var/lib/studycheck/`。

需要从 GitHub Release 拉包/回滚时，仍可用脚本方式：

```bash
cp deploy/deploy.env.example deploy/deploy.env   # 填服务器、版本等
make build-linux
bash deploy/deploy.sh                            # init 首次 / upgrade 升级
```

`deploy.sh` 做这几件事：上传二进制到 `~/studycheck/releases/`，用 `swap` 原子切换软链，
`systemctl restart`，健康检查失败自动回滚上一版本。systemd unit 里限制内存 256M、
开启最小权限与 `Restart=always`。

自动更新走 `/api/v1/system/update`：下载新版本 → sha256 校验 → 原子替换 → 健康检查 →
失败自动回滚。CI 打 tag 会构建并生成 `latest.json`（见 `.github/workflows/release.yml`）。

---

## 安卓 App

### 环境要求

- JDK 17
- Android SDK（`platform-tools`、`platforms;android-35`、`build-tools;35.0.0`）
- Gradle 用仓库自带的 wrapper，无需另装

### 编译

```bash
cd android
export JAVA_HOME=/path/to/jdk17
export ANDROID_HOME=/path/to/Android/Sdk
./gradlew assembleDebug        # 产物：app/build/outputs/apk/debug/app-debug.apk
```

或回到根目录 `make apk`（会读取 `JAVA_HOME` / `ANDROID_HOME`）。

### 安装

- 数据线 + `adb install -r app-debug.apk`
- 或把 APK 传到手机点击安装（需允许「安装未知来源应用」）

### 签名

`android/keystore.properties`（已 gitignore）里配置了 `storeFile / storePassword / keyAlias / keyPassword`，
`assembleRelease` 会自动用它签名；文件不存在时回落到 debug 签名，产物依然可安装。

> 升级安装必须用**同一个签名**，请备份 `android/keystore/release.jks` 与口令。
> CI 发布用 GitHub Secrets：`ANDROID_KEYSTORE_BASE64`（jks 的 base64）与 `ANDROID_KEYSTORE_PASSWORD`。

### 服务器地址

首次启动默认 `http://10.0.2.2:8080/`（模拟器访问宿主机）。真机请在登录页底部
「服务器」或「我的 → 服务器地址」改成电脑局域网 IP（如 `http://192.168.1.5:8080/`）
或线上域名。App 已允许明文 HTTP，仅建议在局域网内使用。

### 功能

- **今日**（一级页面）：未打卡 / 在馆中（实时计时）/ 已完成 三态，出馆后可「再进馆」开新的一段；
  每日目标进度；本周与连续天数；当天分段列表可逐段修正 / 删除；打卡照片以小缩略图展示，点击进详情
- **拍照打卡**：CameraX 全屏预览 → 拍摄 → 确认（地点、备注、**科目**）→ 入队。**只支持相机拍摄，不支持相册导入**
- **科目 / 标签**：打卡时可以填一个科目（如「数学」「英语阅读」），最近用过的一键选；
  修正时间、手动补卡也能改科目；统计页有「各科目时长」卡片，没填的算「未分类」
- **桌面小组件**：长按桌面添加「研读」，直接看今天累计时长与当前状态，一键「进馆 / 出馆」
- **长按图标快捷方式**：进馆打卡 / 出馆打卡，点开就是拍照页
- **快捷设置磁贴**：把「研读进馆」加到下拉通知栏，一点直接拍照打卡
- **深色模式**：跟随系统，也可以手动锁浅色 / 深色（我的 → 外观）
- **照片时间轴**：新标签页「照片」，按月翻看打卡照片与过程留存照片，按天分组、按时刻排序
- **专注（番茄钟）**：今日页一张卡片，25 / 45 分钟专注 + 5 / 15 分钟休息，每 4 段一个长休息；
  进度环 + 到点自动切换，状态存在本地，杀掉 App 再回来接着走（不联网、不需要常驻服务）
- **数据导入**（我的 → 数据导入）：选一个导出的打卡 CSV 导回来。
  日期 + 进馆时间相同的行自动跳过，和已有分段重叠的会被拒掉，逐行给结果
- **每周学习小结**：我的 → 每周学习小结，选周几几点，到点用本地记录算本周时长发一条总结
- **修正打卡时间**：今日详情 / 历史某天详情里点「修正时间」，改进馆、出馆时间，
  也可勾「还没出馆」清空出馆；时长与统计同步更新
- **数据导出**（我的 → 数据导出）：打卡记录 CSV、笔记 CSV、全部照片 ZIP，
  通过系统文件选择器保存到手机，Excel 可直接打开（UTF-8 BOM 不乱码）
- **今日详情**（二级页面）：过程留存
  - **连拍**：一次进入相机可连续拍多张，拍完一次性全部上传（单请求最多 12 张）
  - **笔记**：当天文字笔记，可与某组照片一起保存，也可单独保存
  - 照片与笔记按时间倒序列出，可单条删除；离线时先入本地队列，联网后自动补传
- **历史**：按月翻页，进馆/出馆小缩略图对比；**点任意一天进入当天详情**（打卡照片可看大图、
  进/出馆备注与地点、当天全部过程留存照片与笔记）
- **统计**：连续天数、最长连续、本月/本周、26 周热力图；周报 / 月报（可翻上一期、
  对比时长增减、按星期分布、常去地点）、连续打卡成就与下一个待解锁目标
- **手动补卡**：记录页点「补卡」，选日期、进馆 / 出馆时间、地点与备注，不需要照片；
  同一天可以补多段（按段写入，不会覆盖已有记录）
- **搜索**：记录页顶部搜索框，按备注 / 地点 / 笔记内容，或直接输 `2026-10-24` 找那一天
- **账号安全**：我的 → 修改密码；注销账号（需二次确认密码，数据不可恢复）
- **目标达成提醒**：当日自习时长达到每日目标后发一次通知，同一天不重复
- **我的**：昵称、每日目标、服务器地址、每日提醒开关与时间、待上传队列、退出登录
- **离线优先**：照片先压缩（长边 1600 / WebP q82）落盘并写入 Room 队列，
  WorkManager 指数退避重试；`409` 视为已打卡、`4xx` 丢弃并提示、网络错误保留重试
- **每日提醒**：固定时间检查，若当天没进馆或没出馆则发通知
- **连续在馆提醒**：进馆超过设定时长（默认 4 小时，可调 30 分 ~ 12 小时）还没出馆，
  发一条「还在馆里吗」，通知里直接有「去出馆」按钮；同一次进馆只提醒一次

---

## 接口

完整契约见 [`openapi.yaml`](openapi.yaml)。要点：

- 除注册/登录/健康检查外都要 `Authorization: Bearer <access>`
- 登录、注册、改密码、注销都有失败次数限流（同一 IP / 同一账号 5 分钟内 5 次，超了锁 5 分钟）
- access 2h、refresh 90d 且轮换；旧的 refresh 被重复使用视为泄露，会话整体作废
- 打卡接口支持 `Idempotency-Key` 幂等与 `day` 回溯补传（最多 30 天，`day` 可放表单或 query）
- 一天多段：`GET /api/v1/sessions?day=YYYY-MM-DD` 列当天的段；`POST /api/v1/sessions` 补一段；
  `PATCH /api/v1/sessions/{id}` 只改这一段；`DELETE /api/v1/sessions/{id}` 删这一段。
  `checkins` 仍按天一行（最早进馆、最晚出馆、总时长），统计 / 报表 / 导出不用改
- 分段写入与聚合重算在同一个事务里；段与段之间不允许时间重叠（`409 overlap`，`error.detail`
  会告诉你是哪一段），重叠会把当天的时长算两遍
- 跨天自习：23:50 进馆、00:20 出馆照实记录；出馆时会往回找最近 7 天忘了出馆的段
  （这时照片可省略，时长收敛到那天的 23:59:59，之后可手动改）；`/checkins/today` 会带上
  `open_session` 与 `stale_open` 供客户端提示「先去补出馆」
- 科目：`subject` 是段级字段（`POST /checkins/in` 表单、`POST /sessions`、`PATCH /sessions/{id}`
  都支持，超过 20 字截断，空串表示未分类）；`GET /api/v1/stats/subjects?days=30`（或 `from` / `to`）
  返回各科目时长聚合；`/checkins/report` 带 `subjects`（最多 8 条）；`/search` 命中科目；
  `GET /export/sessions.csv` 多一列「科目」。老客户端不传 `subject` 一切照旧
- 照片时间轴：`GET /api/v1/photos?month=YYYY-MM`（或 `from` / `to`）把打卡照片与过程留存照片按天摊平，
  返回 `prev_month` / `next_month` 方便翻页，同一天内按时刻升序
- 数据导入：`POST /api/v1/import` 上传导出的 CSV（multipart `file`，也支持 JSON `{items:[…]}`）。
  兼容按段 / 按天两种导出列序与英文字段名，时间支持 `HH:MM` 与 `YYYY-MM-DD HH:MM`，一次最多 5000 行；
  重复行跳过、重叠行拒绝、逐行独立事务，返回 `{imported, skipped, failed, errors}`
- 聚合缓存对账：`POST /api/v1/system/recompute`（管理员，可传 `day` / `user_id`）按分段重算
  `checkins`，进程启动时也会自检一次并在日志里提示
- 手动修正：`PATCH /api/v1/checkins/{id}`（`out_at` 传空串 = 清空出馆，时长归零）；
  当天有多段时返回 409 `multiple_sessions`，改走 `PATCH /api/v1/sessions/{id}`
- 手动补卡：`POST /api/v1/checkins`（或 `POST /api/v1/sessions`，无需照片，不能补未来日期）
- 全局搜索：`GET /api/v1/search?q=`（备注 / 地点 / 笔记；`q` 是 `YYYY-MM-DD` 时按日期命中）
- 账号安全：`POST /api/v1/me/password`（改密码，返回新 token）、`DELETE /api/v1/me`（注销账号，需带密码）
- 管理员：`GET /api/v1/system/users`、`POST /api/v1/system/users/{id}/password`（重置密码）
- 周报 / 月报：`GET /api/v1/report?period=week|month&anchor=YYYY-MM-DD`
- 成就：`GET /api/v1/achievements`（连续天数 / 累计天数 / 累计时长三类）
- 数据导出：`GET /api/v1/export/checkins.csv`（按天）、`/api/v1/export/sessions.csv`（按段）、
  `/api/v1/export/entries.csv`、`/api/v1/export/photos.zip`（照片名带 `_HHMM`，一天多段不会互相覆盖）
- 过程留存：`POST /api/v1/entries`（multipart，`photo` 可重复 1~12 次）、`GET /api/v1/entries?day=`、`DELETE /api/v1/entries/{id}`
- 列表支持 `?since=` 增量同步
- `/media/*` 需鉴权（`Authorization` 头 / `sc_media` cookie，兼容旧的 `?token=` 查询参数）
- `X-Timezone` 决定服务端按哪个时区判定「今天」

快速验证：

```bash
curl -s localhost:8080/api/v1/health
TOKEN=$(curl -s -X POST localhost:8080/api/v1/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"username":"demo","password":"demo1234"}' | python3 -c 'import json,sys;print(json.load(sys.stdin)["token"])')
curl -s localhost:8080/api/v1/checkins/today -H "Authorization: Bearer $TOKEN"
```

---

## 数据与备份

需要备份的只有两样：SQLite 数据库（含 WAL）和照片目录。

服务端自带自动备份，管理员在 Web「我的 → 数据备份」里能看到全部备份，支持下载、恢复、删除：

- 每晚 `SC_BACKUP_AT`（默认 03:30）自动备份到 `<安装目录>/data/backups`，保留最近 `SC_BACKUP_KEEP`（默认 14）份
- 首次启动时若一份备份都没有，会立即补一份
- 恢复前会自动先把当前库存档，恢复后所有人需要重新登录

想手动冷备也可以：

```bash
systemctl stop studycheck
tar czf backup-$(date +%F).tar.gz ~/studycheck/data
systemctl start studycheck
```

注意 `-wal` / `-shm` 文件一起打包（或用 `sqlite3 xxx.db ".backup"` 热备份）。

# 从零部署 Vibe Chat

推荐路线是**阿里云轻量应用服务器（中国香港）→ Ubuntu 24.04 → Node.js 24 和 SQLite → Caddy HTTPS → 签名 APK 与在线更新**。它与原项目的部署方式一致。语音转文字用独立的阿里云百炼华北 2（北京）工作空间，可在聊天验证通过后启用。

| 部分 | 原部署选型 | 复刻时准备 |
| --- | --- | --- |
| 云服务器 | 阿里云轻量应用服务器，中国香港，Ubuntu 24.04 x86_64，2 vCPU / 2 GiB / 40 GiB | 从同类规格起步，或使用有公网 IP 的独立 Ubuntu 24.04 主机。安装脚本支持 x86_64 / ARM64。规格、流量和价格以购买页面为准。 |
| 域名与 TLS | sslip.io 临时域名，Caddy 自动签证书 | 有域名就用独立子域名和 `A` 记录。临时测试可用 `vibe.<IPv4 用连字符分隔>.sslip.io`。 |
| 聊天服务 | 单台主机上运行 Node.js 24 LTS、内置 SQLite | `deploy/install.sh` 安装。数据和语音存储在本机。 |
| 转写（可选） | 阿里云百炼华北 2（北京），`qwen3-asr-flash` | 用自己的工作空间、API Key 和音频用量上限。 [转写设置](ASR.md) |
| Android 发布 | JDK 17、SDK 35、Build-Tools 35.0.0、私有签名密钥 | 在电脑构建，服务器提供下载页和在线更新清单。 |

官方入口：[阿里云轻量应用服务器](https://www.aliyun.com/product/swas) · [SSH 连接](https://www.alibabacloud.com/help/en/simple-application-server/user-guide/connect-to-linux-server-remotely) · [sslip.io](https://sslip.io/) · [Caddy 自动 HTTPS](https://caddyserver.com/docs/automatic-https)

## 1. 准备主机、SSH 与域名

1. 在阿里云轻量应用服务器控制台选中国香港和 Ubuntu 24.04 系统镜像。用自己的云账号核对配置、价格、月流量和续费设置，再完成付款。
2. 生成独立 SSH 密钥，在控制台导入 **公钥**、绑定实例并按页面提示重启，或先通过控制台 Web 终端把公钥放进部署用户的 `~/.ssh/authorized_keys`。密钥由操作者保管。使用实际镜像提供的 SSH 登录用户和云控制台核对过的主机指纹。
3. 在云防火墙放行 TCP **80、443**。TCP **22** 仅向部署者来源 IP 开放；后端的 **8080** 只在 `127.0.0.1` 监听。主机若另设防火墙，也放行 80/443。
4. 在 DNS 管理处把域名的 `A` 记录指向这台机器。用 sslip.io 时，例如 `vibe.203-0-113-10.sslip.io` 中的 `203.0.113.10` 换成真实 IP。用 `dig +short` 检查解析；从手机实际使用的网络验证能打开 HTTPS。更换公网 IP 时建议保持自有域名并更新 DNS，已安装 APK 的在线更新来源绑定构建时域名。

在本机仓库根目录设置以下值。示例地址只用于说明，可把 SSH 别名、用户名、公钥路径维护在自己的 `~/.ssh/config`。后面的命令使用同一个域名。

```bash
export VIBE_HOST='ubuntu@203.0.113.10'    # 实际用户和 IP，或 SSH 别名
export VIBE_DOMAIN='vibe.example.com'    # 实际域名，不包含 https://
export VIBE_ORIGIN="https://$VIBE_DOMAIN"
ssh "$VIBE_HOST" 'id; uname -m; cat /etc/os-release'
```

## 2. 安装聊天服务和 HTTPS

在本机打包需要的服务文件。脚本会在**专用主机**上设置 `/etc/caddy/Caddyfile`，已有其他站点时应人工合并对应站点配置。

```bash
tar -czf /tmp/vibe-chat-server.tar.gz \
  server/package.json server/src deploy/install.sh deploy/release.py \
  deploy/backup.sh deploy/vibe-backup.service deploy/vibe-backup.timer
scp /tmp/vibe-chat-server.tar.gz "$VIBE_HOST:/tmp/vibe-chat-server.tar.gz"
ssh -t "$VIBE_HOST" "sudo install -d /opt/vibe-chat && \
  sudo tar --no-same-owner -xzf /tmp/vibe-chat-server.tar.gz -C /opt/vibe-chat && \
  sudo bash /opt/vibe-chat/deploy/install.sh '$VIBE_DOMAIN'"
curl --fail --show-error "$VIBE_ORIGIN/health"
```

安装脚本下载并校验 Node.js 24，安装 Caddy，建立非 root 用户 `vibe`，启用 `vibe-chat`、`caddy` 和 `vibe-backup.timer`。首次健康检查应包含 `"ok":true` 和 `"transcription":"not_configured"`。Caddy 将 HTTPS 请求转给本地 Node，将 `/downloads/` 映射到 `/srv/vibe-downloads`。首次发布 APK 后下载页可用。

若检查失败，在主机运行 `sudo systemctl status vibe-chat caddy vibe-backup.timer`、`sudo journalctl -u vibe-chat -u caddy -n 80 --no-pager`，以及 `curl -fsS http://127.0.0.1:8080/health`。本地健康、公网失败时，核对 DNS、云防火墙与 Caddy 的证书日志。对外切勿公开完整环境变量或凭据。

## 3. 构建并签名 Android App

在开发电脑安装 JDK 17、Python 3.9+ 和 Android SDK 的 **Platform 35、Build-Tools 35.0.0、Platform-Tools**（Android Studio SDK Manager 或已配置的 `sdkmanager`）。命令在 Bash 里执行，`JAVA_HOME`、`ANDROID_SDK_ROOT` 换为本机绝对路径。客户端包名是 `chat.vibe.app`，如已有其他实例的 Vibe Chat，用单独测试设备或模拟器验证自己的签名包。

```bash
export JAVA_HOME='/path/to/jdk-17'
export ANDROID_SDK_ROOT='/path/to/android-sdk'
export PATH="$JAVA_HOME/bin:$PATH"
# 只在第一次创建；换版本时使用同一文件与同一别名。
install -d -m 700 "$HOME/.vibe-chat"
export VIBE_SIGNING_KEYSTORE="$HOME/.vibe-chat/release.p12"
export VIBE_KEY_ALIAS='vibe-release'
keytool -genkeypair -keystore "$VIBE_SIGNING_KEYSTORE" -storetype PKCS12 \
  -alias "$VIBE_KEY_ALIAS" -keyalg RSA -keysize 2048 -validity 10000
chmod 600 "$VIBE_SIGNING_KEYSTORE"
read -r -s -p '签名库密码: ' VIBE_STORE_PASSWORD; printf '\n'
export VIBE_STORE_PASSWORD
VIBE_VERSION_CODE=30 VIBE_VERSION_NAME=0.6.8 python3 scripts/build.py
unset VIBE_STORE_PASSWORD
```

`VIBE_ORIGIN` 编译进 APK，既是初始聊天地址也是可信更新来源。App 登录页面的「服务地址」可切换聊天服务器，在线更新仍属于构建者。开发可不提供 `VIBE_SIGNING_KEYSTORE`，脚本会在 `build/` 生成并复用调试密钥。公开分发用上面的独立私钥，备份到安全位置。签名丢失会影响覆盖升级。服务器只接收已签名的 APK。

## 4. 公布首个 APK 和在线更新

在本机读取签名包内的版本、包名、最低 SDK 和域名，计算文件大小与 SHA-256。下载地址含 `versionName` 和 `versionCode`，便于客户端区分缓存。

```bash
python3 deploy/release.py prepare build/VibeChat-0.6.8.apk "$VIBE_ORIGIN" \
  --out releases/30 --notes '首次自托管发布'
ssh "$VIBE_HOST" 'mkdir -p ~/vibe-releases'
scp -r releases/30 "$VIBE_HOST:vibe-releases/"
ssh -t "$VIBE_HOST" "sudo python3 /opt/vibe-chat/deploy/release.py publish \
  \"\$HOME/vibe-releases/30\" '$VIBE_ORIGIN'"
curl --fail --show-error "$VIBE_ORIGIN/downloads/latest.json"
```

发布脚本核对文件字节，依次写入 APK、下载页和 `latest.json`，并阻止用不同文件覆盖已发布版本。一次只运行一个发布进程。打开 `https://你的域名/` 下载 APK。发布下一版时保持域名、包名和证书，增加版本号，例如 `VIBE_VERSION_CODE=31 VIBE_VERSION_NAME=0.6.9`。按该版本替换路径并复走本节。App 在「我 → 检查更新」下载，经 Android 系统安装确认后覆盖升级。安装时核对 APK 版本和证书；如果显示签名不符，用原密钥重建，避免为验证而卸载有数据的 App。

## 5. 可选启用阿里云百炼

见 [ASR.md](ASR.md)。用华北 2（北京）工作空间生成专用 API Key，端点采用 `https://<工作空间ID>.cn-beijing.maas.aliyuncs.com/compatible-mode/v1/chat/completions`。录音由后端用 Base64 WAV 提交，识别文字写回原会话。没有配置 API Key 时，文字聊天和语音收发正常工作。转写可能产生费用，并把原音频发送给阿里云服务，请依据部署者的账户预算与用户同意开启。

## 6. 验收及维护

- **服务**：`curl -fsS "$VIBE_ORIGIN/health"`，再检查 `/downloads/latest.json` 和其中的 APK URL 都返回 200；下载后对比清单中的 `sha256` 和 `size`。
- **聊天**：在两台 Android 设备或「一台手机 + 一台模拟器」用测试账号注册，扫码加好友并接受。同会话互发文字和语音，确认播放、悬浮按住录音、上滑取消、耳机或允许外放时的自动播放。群聊另测二维码入群。
- **转写**：开通后 `/health` 为 `enabled` 只代表配置加载。发一条有真实人声的短语音，确认文字生成，且音频先可播放。
- **更新**：安装较早签名版后发布更高 `versionCode`，在旧版触发在线更新，在安装器确认后核对版本、账号和会话是否保留。
- **备份**：主库 `/var/lib/vibe/vibe.sqlite`。每日备份定时器将已校验的 SQLite 在线备份压缩到 `/var/backups/vibe`，大约保留 7 天。用 `sudo systemctl start vibe-backup.service` 手动验证一次，再检查备份文件。重要数据应另外保存访问受控的异地副本。恢复时先停服务、保留当前主库及 WAL/SHM，再把校验过的备份还原到原路径，设为 `vibe:vibe` 和 `0600` 后启动。
- **服务端更新**：先手动备份并保留现有 `server/`，检查新代码后替换并 `sudo systemctl restart vibe-chat`。保持 `/var/lib/vibe` 和 `/etc/vibe-chat/asr.env`，客户端 APK 独立发布。服务器以 100 个注册账号作为默认上限，修改安装脚本生成的 `MAX_USERS` 后需 `daemon-reload` 并重启。

[给部署 AI 的任务说明](AI-DEPLOY.md) · [回到项目首页](../README.md)

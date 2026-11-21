# 语音识别配置

Vibe Chat 0.5.0 默认给每条语音附加转写文字。录音原格式为 16 kHz / 单声道 / 16-bit PCM、0.5–30 秒；服务器封装为 WAV，以 Base64 请求阿里云百炼 `qwen3-asr-flash`。音频不设公开下载链接，密钥不放入 APK。

## 在自己的实例上启用转写

登录[阿里云百炼控制台](https://bailian.console.aliyun.com/)，在华北 2（北京）地域开通模型服务，记录工作空间 ID，创建供此项目专用的 API Key。如果控制台支持按模型授权，限制为 `qwen3-asr-flash`。确认计费后再发起实际调用。密钥只显示一次，直接保存到服务器 `/etc/vibe-chat/asr.env`（目录 0700、文件 0600、root 所有），不要贴入源码、聊天或日志。先以具备管理员权限的用户创建并编辑文件：

```bash
sudo install -d -m 700 /etc/vibe-chat
sudo touch /etc/vibe-chat/asr.env
sudo chmod 600 /etc/vibe-chat/asr.env
sudoedit /etc/vibe-chat/asr.env
```

将下面的占位值换成你自己的值，保存后再执行重启命令：

```ini
ASR_API_KEY=在服务器填写专用密钥
ASR_ENDPOINT=https://业务空间ID.cn-beijing.maas.aliyuncs.com/compatible-mode/v1/chat/completions
ASR_MODEL=qwen3-asr-flash
ASR_DAILY_SECONDS=3600
ASR_MONTHLY_SECONDS=108000
```

systemd 服务通过 `EnvironmentFile=/etc/vibe-chat/asr.env` 读取。修改服务配置后 `systemctl daemon-reload`，然后 `systemctl restart vibe-chat`。查看 `/health` 的 `transcription` 字段，配置加载成功为 `enabled`；这只表示配置有效，不代表账户余额或模型调用已验证。实际验证请发送独立测试账号的短语音，检查 `transcript_status=ready`。

安装脚本已提供可选的 `EnvironmentFile`。若暂时移除识别凭据，原语音仍可正常收发与播放；恢复凭据后自动处理尚未开始的任务。已有失败任务可在消息下方单独重试文字。

## 用量与恢复

- 每日默认上限 3600 秒，月上限 108000 秒，按北京时间结算。预算为应用级音频用量限制。开通前查阅官方计费页，按实际模型、地域和价格估算费用。
- 单任务并发；每个请求先持久化预留音频秒数，即使失败也保守计入预算。网络/限流/服务端暂时错误自动重试一次；用户可重试，含崩溃恢复最多共三次。群成员重试共享同一任务，避免重复计费。
- 达到用量上限后保留语音，文字任务等待额度恢复。账户欠费或凭据失效不会阻止聊天；检测到此类错误后暂停提交一分钟，避免整个待处理队列连续失败。更新凭据并重启服务后，未超过重试上限的此类失败任务自动恢复。请由账号持有人在阿里云充值；不自动充值、不自动购买资源包。
- 新消息自动排队；打开旧会话时只为当前返回的最近 50 条消息补充任务。识别结果以 message_id 保存、跟随消息删除；只有有权查看原消息的成员能读取文字。加入前的内容不会泄露给后来成员。
- 日志仅记录 `AUTH`、`BALANCE`、`HTTP_...` 等错误码，不记录密钥、音频或识别内容。
- SQLite schema 3 只增加表。部署前在线备份；回滚应用代码不需要删新表。恢复数据库需沿用原有停服备份流程。

## 参考与实现差异

参考 Type4Me（MIT，commit `f21339e214d65e9bd4d191ff5136513902e7473b`）中的 `BufferedBatchASRClient.swift` 与 `BatchASRTranscribers.swift`：结束录音后批量识别、WAV 包装、空白人声与网络错误分开。Vibe Chat 自行实现 Node.js 持久队列，采用官方支持的 qwen3-asr-flash OpenAI-compatible 接口；Type4Me 的新 qwen-audio-3.1 模型使用原生 DashScope 接口，二者端点不能直接互换。未移植其 Swift 源码或界面。

- https://github.com/joewongjc/type4me
- https://help.aliyun.com/zh/model-studio/qwen-asr-api-reference
- https://help.aliyun.com/zh/model-studio/model-pricing

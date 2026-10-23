# 平安打卡助手 PinganDakaHelper

中南大学智慧学工「平安打卡」自动打卡安卓 App（学生端，服务大厅 → 日常事务 → 平安打卡）。

> ⚠️ 仅供个人学习与自用。请遵守学校规定，如实打卡；因使用本工具产生的一切后果由使用者自行承担。

## 功能

- **每天 19:05 自动打卡**（时间可改）：系统精确闹钟触发，成功后推送通知；失败（未开放/网络差/登录失效）每 5 分钟自动重试，最多 8 次；重启后自动恢复
- **手动打卡**：打开 App 自动检查并完成当日打卡，主页「立即打卡」可随时重试
- **自动登录**：首次登录一次（支持图形验证码/短信验证码人工输入），学号口令仅存本机；登录态过期自动补登；遇到必须人工的验证场景会推送通知提醒
- **零第三方依赖**：Kotlin + 系统 API（HttpURLConnection / AlarmManager / WebView），安装包不含任何个人信息

## 工作原理

直接调用与智慧学工 H5 前端完全一致的接口（非模拟点击）：

| 步骤 | 说明 |
| --- | --- |
| CAS 登录 | `ca.csu.edu.cn/authserver/login`，口令按页面盐值 AES-CBC 加密；回调链取得 `token`（网关请求头）与 `casual`（DES 密钥） |
| 查状态 | `POST /znzhxgpt/qxj/qxj-padkglxx/queryKqDkbc`（`dklb=PA`）→ 班次 / 是否已打卡 / 是否可打卡 |
| 提交打卡 | `POST /znzhxgpt/qxj/qxj-padkglxx/xspadk`，平铺参数 `jd/wd/dkbc/dkdz` |

请求体为 JSON 的 DES-ECB-PKCS7 加密 hex（密钥取 `casual` 前 8 字节，与前端 crypto-js 行为逐字节一致）。

## 构建

```bash
# 本地（JDK 17 + Android SDK 34）
cd PadkApp
gradle assembleDebug
# 产物：PadkApp/app/build/outputs/apk/debug/app-debug.apk
```

或直接把本仓库推到 GitHub——`.github/workflows/build-apk.yml` 会在 Actions 里自动编译并上传 APK 工件。

## 目录

```
PadkApp/                       # Android 工程（com.junxi.padka）
  app/src/main/java/com/junxi/padka/
    CasClient.kt               # CAS 登录 / 自动补登（含诊断日志）
    Crypto.kt                  # DES-ECB（接口请求体）+ AES-CBC（CAS 口令）
    PadkApi.kt                 # 智慧学工网关接口
    PunchEngine.kt             # 打卡编排（登录→查状态→提交）
    AlarmScheduler.kt 等       # 19:05 定时调度、重试、开机恢复
    MainActivity.kt 等         # 界面（主页/设置/内置登录页）
PinganDakaHelper-debug.apk     # 本地构建产物（不入库）
PinganDakaHelper-使用说明.md    # 面向使用者的完整说明
```

## 使用

见 [`PinganDakaHelper-使用说明.md`](PinganDakaHelper-使用说明.md)。首次配置约 2 分钟：内置登录页登录一次 → 设置里保存学号口令、开定时、记录默认位置、授三个权限。

## 常见问题

- 提示需要验证码/短信验证 → 打开 App「登录」人工完成一次，之后恢复自动
- 提示口令不正确 → 改过校园网密码，到「设置」更新
- 定时没执行 → 检查通知/精确闹钟/电池优化权限；国产系统需允许自启动、省电策略改无限制

## License

MIT

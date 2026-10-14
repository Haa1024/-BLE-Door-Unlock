# 共享开门

一键 BLE 蓝牙开门 Android App，适用于住理生活门锁系统。

## 免责声明

本软件仅供学习研究蓝牙低功耗（BLE）通信协议使用，**不会收集、上传或分享任何个人信息**，所有数据仅存储在设备本地。

在使用本软件获取门锁凭证及开门前，请确认你对该门锁设备拥有合法使用权（如已获授权、为合法租户等）。请勿用于非法侵入他人住所或任何违法违规用途。因使用本软件产生的一切后果由使用者自行承担。

## 功能

- 🔓 **感应场主界面**：整屏浅绿同心波纹，点击中心即可开门，开锁时涟漪自动加快收缩
- 📶 **手机碰锁感应区直接开门**（官方 0xB1 加密协议完整移植，密钥轮换，见 README-NFC.md）
- 📶 **解锁屏幕后碰锁直接开门**（不必打开 App，非门锁标签完全静默，设置页可关）
- 📶 NFC 磁标签碰一碰开门（设置页一键写卡，碰标签自动 BLE 开门）
- 🔄 自动刷新凭证（离线次数用完时自动从服务器获取新 chainKey）
- 🧩 桌面小组件（可拖放调整大小）
- 🔑 住理生活账号登录（手机号密码 / **支付宝一键登录**），自动获取门锁参数
- 📝 支持手动填写凭证参数
- 🎨 东南大学 VIS 官方配色（东大绿 `#4C7D2C`），零阴影极简卡片排版

## 下载

从 [Releases](../../releases) 页面下载最新 APK 直接安装；也可在 [Actions](../../actions) 每次构建的 Artifacts 里下载。

## GitHub Actions 自动打包

本项目已内置 CI（`.github/workflows/android.yml`）：推送代码后 GitHub 自动编译 Release APK。

1. 把项目推到 GitHub（首次）：
   ```bash
   git init && git add -A && git commit -m "init"
   git branch -M main
   git remote add origin https://github.com/<你的用户名>/<仓库名>.git
   git push -u origin main
   ```
2. 构建 APK：推送任意代码自动触发；或在仓库 Actions 页面点 **Build APK → Run workflow** 手动触发。
3. 取 APK：Actions 运行详情页底部 **Artifacts** 下载；打 `v*` 标签（`git tag v1.0 && git push --tags`）会自动发布到 **Releases**。
4. 签名密钥（三选一，CI 自动识别）：
   - **密钥入库（最简单）**：`.gitignore` 排除了 `*.keystore`，需强制提交：`git add -f release.keystore`。方便，但公开仓库等于密钥公开，自用可接受；
   - **Secrets 注入（更安全）**：仓库 Settings → Secrets and variables → Actions 新建 `KEYSTORE_BASE64`，值为 `base64 -w0 release.keystore` 的输出；
   - **都不配**：CI 现场生成临时密钥，APK 可装可测但无法覆盖升级正式签名版。

## 使用方法

### 1. 编译安装（可选）

如需自行编译：

```bash
./gradlew assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
```

### 2. 配置凭证

**方式一（推荐）**：在设置页登录住理生活账号 —— 手机号 + 密码，或点「支付宝一键登录」；登录后点"自动获取参数"

**方式二**：手动填写设备 ID、项目 ID、凭证 ID、chainKey、MAC 地址

### 3. 开门

**感应场**：点主界面正中央的圆盘（或底部按钮）即开门 —— 波圈的收缩节奏会跟着状态走，
待机缓慢、开门时加快。也可以加一个桌面小组件一键开门，长按可拖放调整大小。

**碰锁**：**解锁屏幕后**把手机贴上门锁感应区，NFC 直接开门，不必打开 App。

> ⚠️ 锁屏状态下碰锁无效（Android 10 起的后台启动限制），必须先解锁屏幕。

> 部分手机小组件入口隐藏较深，如真我手机：长按桌面 → 卡片 → 全部卡片 → 滑到底部 → 插件 → 找到「共享开门」。

## License

MIT

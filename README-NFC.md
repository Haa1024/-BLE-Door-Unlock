# NFC 开锁 — 原理与逆向记录

> 本文档记录"手机碰锁感应区开门"功能的完整逆向过程与实现原理。

## 一、真实架构（重要！与直觉相反）

对官方住理生活 App v3.11.51 的逆向分析结论：

```
┌──────────┐  NFC 读卡器模式   ┌──────────────┐
│  手机     │ ───────────────→ │  门锁感应区    │
│  (读卡器) │ ←─────────────── │  (NDEF 标签)  │
└──────────┘   0xB1 加密指令    └──────────────┘
```

- **手机是读卡器，门锁才是"卡"**（不是 HCE 卡模拟！）
- 官方 APK 中**没有任何 HostApduService**（Manifest 全量检查确认）
- 门锁感应区模拟一个 **NDEF 标签**，内容为 URL：
  `https://uc-zhuli.whxinna.com/?t=lock&d=<设备ID>`
- **实测补充 (v1.4.1)**：部分锁型感应区是**不带 NDEF 的纯 NfcA 卡**，
  UID 以 ASCII `"XN-"` 开头（与 BLE 广播名 `XN-<设备ID>` 同源，剩余 4 字节疑似内嵌设备ID）
  → 本 App 碰到无 NDEF 标签时自动回退使用设置页已配置的设备ID；
  官方 App 同样不依赖 NDEF（走 TECH_DISCOVERED 分发 + 账号内已配对锁信息）
- 官方 App 的 `NfcPendingActivity` 注册了 NDEF/TECH_DISCOVERED（这就是官方"必须先点开锁再碰"的原因——App 前台准备凭证）
- 官方原生层通过 dsbridge 暴露 5 个方法给 H5：`zl.nfc.init / release / onTagDiscovered / onTagReaderFail / write`

## 二、0xB1 开锁协议（移植自官方 H5 源码）

官方 APK 的 `assets/nfc_test.html` 残留页 + H5 业务包 `assets/apps/zhuli.zip`
（Vue3 + Vite，`nfcOpenDoorCommand.js` / `useDoorLock.js` / `hooks.js`）是协议完整来源。

### 开锁指令（手机 → 锁）

```
[0xB1][0x0D][密文长度][RC4 密文][CRC8]
```

- 明文 = `projectId(4字节 LE) + chainKey(32字节)`
- RC4 密钥 = `deriveDeviceKey(deviceId)`（TEA 变换，固定种子 0xAC 0xAB 0xBC 0xDA…，与 BLE 协议共用同一套 Crypto）
- CRC8 输入 = `[0x0D, 密文长度, ...明文]`（对明文计算，多项式 0x07，与 BLE 同表）

### 锁的响应（锁 → 手机）

```
[0xB1][子命令][密文长度][RC4 密文][CRC8]
```

- 解密后明文：`[0]=结果码, [1..3]=保留, [4..35]=新chainKey(32字节), [36..39]=seq(LE)`
- CRC8 输入 = `[子命令, 密文长度, ...解密明文]`
- 子命令 echo `0xF0`=锁回报数据CRC错误 / `0xF1`=锁回报未知命令
- 结果码：`0=成功, 10=门锁状态已打开, 14=用户已删除, 15=项目ID不一致, 255=未知错误`（完整表见 Crypto.NFC_ERROR_MESSAGES）

### 密钥轮换

开锁成功后锁返回**新 chainKey**，App 必须保存（与官方 H5 行为一致），
下次开锁（BLE 和 NFC 通用）使用新密钥。

### 激活流程（首次配对）

走服务器中继：H5 调服务端接口拿指令包 → 写入标签 → 响应回传服务端 → 服务端下发下一包。
凭证（credential_id + chainKey）最终由服务端下发。本 App 简化：直接在设置页填入 BLE 激活时获得的凭证，NFC/BLE 通用。

## 三、传输通道（已实测确定）

官方原生层（`zl.nfc.write`）的标签级通信实现在加固的 dex 里（梆梆加固
libSecShell.so，assets/classes0.jar 为加密载荷，无法静态脱壳）。

但真机实测已经给出答案：**只有 `NfcA.transceive(0xB1帧)` 一条通道有效**。

```
门锁感应区 = ISO14443-A Type 2 标签，ATQA=4400 / SAK=0，techList 仅 NfcA
  · SAK bit6 未置位 → 不支持 ISO/IEC 14443-4 → IsoDep 必然不存在
  · Type 2 不是 Mifare Classic
  · 0xB1 帧也不是标准 APDU，不做 iso7816 封装
```

因此 `NfcDoorLockManager.tryTransports` 已从「四通道逐一试探」**收敛为单一 NfcA**：

1. 每轮重新 `NfcA.get(tag)`（上一轮 `close()` 过的实例不复用）
2. `transceive(0xB1帧)`，超时 2 秒；响应必须以 `0xB1` 开头才算有效
   —— 否则会把错误响应当成果提前返回
3. 失败重试 3 次，轮间留 150ms 空档（TagLost 后立刻重连，
   NFC 控制器往往还没从射频异常里恢复，不留空档等于只有第一次算数）

全程有日志（设置 → NFC 通讯日志），无论成功失败都能定位问题。

## 四、使用说明

1. **手机碰锁感应区开门（App 前台）**：打开本 App，手机贴住锁的 NFC 感应区 1~3 秒
   - 前提：设置页已填入 projectId + chainKey（BLE 激活获得的凭证）
   - NFC 保持开启；碰锁时如弹出小米钱包刷卡动画，请在钱包中关闭"门钥匙"功能
2. **碰锁直接开门（不必打开 App）**：**先解锁屏幕**，把手机贴上门锁感应区即可
   - ⚠️ 锁屏状态无效 —— Android 10 起限制后台启动 Activity，系统不会在锁屏时
     投递 NFC 分发。实测确认锁屏碰锁毫无反应，解锁后才会走到开门流程
   - 非门锁标签（校园卡 / 公交卡 / 银行卡）会立刻静默退出，不显示任何界面
   - 设置页「碰门锁本体直接开门」开关可整体关掉此能力（动态禁用分发组件）
3. **磁标签开门**：设置 → 写入 NFC 标签 → 碰一张空白标签 → 贴门边，碰一下走 BLE 开门
4. **排查问题**：设置 → 查看 NFC 通讯日志（实时刷新，可一键复制）

## 五、版本历史

- v1.3-nfc：初版（误判为 HCE 架构，框架预留）
- v1.4-nfc：**架构修正** — 确认官方为"手机读卡器 + 门锁 NDEF 标签"，
  完整移植 0xB1 协议（RC4/CRC8/密钥轮换），多通道试探 + 全量日志
- v1.4.1-nfc：**无 NDEF 回退** — 实测锁感应区为无 NDEF 的 NfcA 卡 (UID "XN-" 前缀)，
  碰锁不再拒绝而是自动用已配置设备ID发 0xB1；修正 SKIP_NDEF_CHECK 下 NDEF 读不出的问题；
  增加 UID ASCII/内嵌设备ID 大小端诊断日志（版本 code 6）
- v1.4.0：**通道收敛 + 碰锁直发 NFC 指令** — tryTransports 从四通道盲试收敛为
  单一 NfcA（实测 SAK=0 排除 IsoDep/Classic）；每次重试重新 connect + 150ms 空档；
  后台碰锁改为「NFC 三次失败才回退蓝牙」；修正"锁屏也能开门"的错误文案（版本 code 8）

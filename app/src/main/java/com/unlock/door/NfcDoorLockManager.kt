package com.unlock.door

import android.nfc.Tag
import android.nfc.tech.Ndef
import android.nfc.tech.NdefFormatable
import android.nfc.tech.NfcA
import android.os.Handler
import android.os.Looper
import java.util.concurrent.Executors

/**
 * NFC 门锁开门管理器 — 手机作为读卡器, 门锁感应区为 NDEF 标签
 *
 * 官方协议 (逆向自 H5): 碰锁后
 *   1. 读标签 NDEF URL: https://uc-zhuli.whxinna.com/?t=lock&d=<设备ID>
 *      (实测部分锁型感应区不带 NDEF, UID 以 ASCII "XN-" 开头 → 回退用已配置设备ID,
 *       官方 App 同样不依赖 NDEF: 走 TECH 分发 + 账号内已配对锁信息)
 *   2. 写入 0xB1 开锁指令: [B1][0D][密文长度][RC4(projectId+chainKey)][CRC8]
 *   3. 锁返回新 chainKey (密钥轮换) + 结果码
 *
 * 传输通道由官方原生层实现 (dex 已加固), 本单例对多种通道逐一试探并记录全量日志,
 * 命中 0xB1 合法响应即成功; 未命中时把日志发回分析即可。
 */
object NfcDoorLockManager {

    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    /** 写卡模式: 开启后碰到的标签被写入 unlockdoor:// 开门指令 (磁标签方案) */
    @Volatile
    var writeCardMode: Boolean = false

    /** UI 回调 (主线程): 开锁结果 */
    var onUnlockResult: ((ok: Boolean, message: String) -> Unit)? = null

    // ─── 通讯日志 (环形缓冲) ───
    private val logBuf = ArrayDeque<String>()

    fun appendLog(line: String) {
        val ts = java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.US)
            .format(java.util.Date())
        synchronized(logBuf) {
            logBuf.addLast("[$ts] $line")
            while (logBuf.size > 300) logBuf.removeFirst()
        }
    }

    fun snapshotLog(): List<String> = synchronized(logBuf) { logBuf.toList() }

    fun clearLog() = synchronized(logBuf) { logBuf.clear() }

    // ─── 标签处理入口 (ReaderMode 回调线程调用) ───

    fun handleTag(tag: Tag) {
        executor.execute {
            val result = runCatching { processTag(tag) }
                .getOrElse { appendLog("✗ 处理异常: ${it.message}"); null }
            result?.let { (ok, msg) ->
                mainHandler.post { onUnlockResult?.invoke(ok, msg) }
            }
        }
    }

    /** @return (是否成功, 提示消息); null = 仅记录日志不提示 */
    private fun processTag(tag: Tag): Pair<Boolean, String>? {
        appendLog("═══ 标签发现 ═══")
        appendLog("技术: ${tag.techList.joinToString(",") { it.substringAfterLast('.') }}")
        appendLog("UID: ${Crypto.bytesToHex(tag.id)}")
        diagnoseUid(tag.id)

        // ── 0. 读 NDEF → 判断标签类型 ──
        val (uri, ndefDeviceId) = readTagIdentity(tag)

        if (writeCardMode) {
            // 写卡模式交回 MainActivity 处理 (需要 Toast + 退出写卡模式)
            return null
        }

        // 磁标签 (unlockdoor://open): 走 BLE 开门
        if (uri?.scheme == "unlockdoor") {
            appendLog("识别为本 App 磁标签 → 触发 BLE 开门")
            return Pair(true, STICKER_UNLOCK)
        }

        // ── 0.5 设备ID来源: NDEF URL 优先; 无 NDEF 时回退已配置设备ID ──
        var deviceId: Int? = ndefDeviceId
        if (deviceId == null) {
            val cfgId = if (SettingsManager.isInitialized()) SettingsManager.deviceId else 0
            if (cfgId > 0) {
                appendLog("标签未提供 NDEF 设备ID → 回退使用已配置设备ID: $cfgId (BLE 蓝牙名 XN-$cfgId)")
                deviceId = cfgId
            } else {
                appendLog("✗ 标签无 NDEF 且未配置设备ID, 无法构造开锁指令 (请先完成设置页配置)")
                return Pair(false, "无法识别门锁标签")
            }
        }
        appendLog("门锁设备ID: $deviceId")

        // ── 1. 凭证检查 ──
        if (!SettingsManager.isInitialized() || !SettingsManager.isConfigured()) {
            appendLog("✗ 未配置 chainKey, 请先在设置页填入凭证 (BLE 激活获得)")
            return Pair(false, "请先在设置页配置凭证")
        }
        val chainKey = SettingsManager.chainKey
        appendLog("当前凭证: ${chainKey.take(12)}…(hex长度${chainKey.length})")

        // ── 2. 构造 0xB1 开锁指令 ──
        val cmd = Crypto.buildNfcOpenCommand(deviceId, SettingsManager.projectId, chainKey)
        appendLog("→ 指令: ${Crypto.bytesToHex(cmd)}")

        // ── 3. 逐通道试探传输 ──
        val response = tryTransports(tag, cmd)
        if (response == null) {
            appendLog("✗ 所有通道未获得有效响应 (日志已记录, 可反馈分析)")
            return Pair(false, "NFC 未获得门锁响应")
        }

        // ── 4. 解析 0xB1 响应 ──
        val result = Crypto.parseNfcResponse(response, deviceId)
        if (result.error != null) appendLog("✗ 解析: ${result.error}")
        if (!result.success) {
            appendLog("✗ 开锁失败: ${result.message} (结果码=${result.resultCode})")
            return Pair(false, result.message.ifEmpty { result.error ?: "开门失败" })
        }

        // ── 5. 密钥轮换 (锁返回新 chainKey, 与官方 H5 行为一致) ──
        result.newChainKeyHex?.let { newKey ->
            if (newKey.isNotBlank() && !newKey.matches(Regex("^0+$"))) {
                appendLog("凭证轮换: ${chainKey.take(12)}… → ${newKey.take(12)}…")
                SettingsManager.chainKey = newKey.lowercase()
            }
        }
        appendLog("✓ ${result.message} (seq=${result.seq})")
        return Pair(true, result.message)
    }

    /** 磁标签触发 BLE 开门的特殊标记 */
    const val STICKER_UNLOCK = "__STICKER_BLE_UNLOCK__"

    /**
     * UID 诊断：住理门锁感应区 UID 以 ASCII "XN-" 开头，
     * 其后 4 字节小端读取再右移 8 位即 deviceId（实测 58 4E 2D 00 81 D4 22 → 2282625）。
     */
    private fun diagnoseUid(uid: ByteArray) {
        val ascii = uid.joinToString("") {
            val c = it.toInt() and 0xFF
            if (c in 0x20..0x7E) c.toChar().toString() else "·"
        }
        appendLog("UID ASCII: \"$ascii\"")
        appendLog("UID 原始字节: ${Crypto.bytesToHex(uid)}")

        val id = deviceIdFromUid(uid) ?: run {
            appendLog("UID 无 XN- 前缀 → 不是住理门锁感应区")
            return
        }
        appendLog("UID 含 XN- 前缀 → 确认为住理门锁感应区，内嵌 deviceId = $id")

        val cfgId = if (SettingsManager.isInitialized()) SettingsManager.deviceId else 0
        if (cfgId > 0) {
            appendLog(if (id == cfgId) "与当前配置一致 ✓" else "与当前配置不一致 (配置为 $cfgId)")
        }
    }

    /**
     * 从住理门锁 UID 反推 deviceId。
     *
     * 实测样本：UID = `58 4E 2D 00 81 D4 22`
     *  - 前 3 字节是固定 ASCII 前缀 `"XN-"`（与 BLE 广播名 `XN-<deviceId>` 同源）
     *  - 后 4 字节按小端读 = 0x22D48100，右移 8 位 = 0x22D481 = 2282625 = deviceId
     *    （等价说法：UID[4..6] 逆序即为 deviceId 的 3 字节大端表示）
     *
     * @return 反推出的 deviceId；若并非住理门锁标签则返回 null
     */
    fun deviceIdFromUid(uid: ByteArray): Int? {
        if (uid.size < 7) return null
        if (uid[0] != 'X'.code.toByte() ||
            uid[1] != 'N'.code.toByte() ||
            uid[2] != '-'.code.toByte()
        ) return null

        val raw = (uid[3].toInt() and 0xFF) or
                ((uid[4].toInt() and 0xFF) shl 8) or
                ((uid[5].toInt() and 0xFF) shl 16) or
                ((uid[6].toInt() and 0xFF) shl 24)
        val id = raw ushr 8
        return if (id > 0) id else null
    }

    // ─── NDEF 读取与设备ID提取 ───

    /** @return (URI, 设备ID) — 可能都为 null */
    private fun readTagIdentity(tag: Tag): Pair<android.net.Uri?, Int?> {
        val ndef = Ndef.get(tag) ?: run {
            NdefFormatable.get(tag)?.let {
                appendLog("标签可格式化但无 NDEF 内容 (NdefFormatable)")
            } ?: appendLog("标签不支持 NDEF")
            return Pair(null, null)
        }
        return try {
            ndef.connect()
            // 读卡器模式带 FLAG_READER_SKIP_NDEF_CHECK → cachedNdefMessage 可能为 null, 主动读一次
            val msg = runCatching { ndef.ndefMessage }.getOrNull() ?: ndef.cachedNdefMessage
            ndef.close()
            if (msg == null) {
                appendLog("NDEF 消息为空")
                return Pair(null, null)
            }
            for (rec in msg.records) {
                val uri = runCatching { rec.toUri() }.getOrNull()
                if (uri != null) {
                    appendLog("NDEF URI: $uri")
                    val t = uri.getQueryParameter("t")
                    val d = uri.getQueryParameter("d")
                    if (d != null && (t == "lock" || t == "look")) {
                        val id = d.toIntOrNull()
                        if (id != null) return Pair(uri, id)
                    }
                }
            }
            appendLog("NDEF 中无 t=lock&d= 参数")
            Pair(null, null)
        } catch (e: Exception) {
            appendLog("NDEF 读取失败: ${e.message}")
            runCatching { ndef.close() }
            Pair(null, null)
        }
    }

    // ─── 传输通道（已收敛为 NfcA 单一通道）───

    /** NfcA 发送重试次数（NFC 通信易被标签移动打断，官方 H5 同样是 3 次） */
    private const val TRANSPORT_RETRIES = 3

    /**
     * 发送 0xB1 指令并取回响应。
     *
     * 为什么只走 NfcA（实测结论，2026-10-14 桃园七舍 332）：
     *   门锁感应区是 ISO14443-A Type 2 标签，ATQA=4400 / SAK=0，techList 仅 NfcA。
     *   SAK 的 bit6 未置位 → 不支持 ISO/IEC 14443-4 → IsoDep 必然不存在；
     *   Type 2 也不是 Mifare Classic。官方原生层同样走 NfcA 裸帧直传
     *   （0xB1 帧不是标准 APDU，不做 iso7816 封装）。
     *   因此原先的「四通道逐一试探」已无意义，收敛为单一通道。
     *
     * 校验：响应必须以 0xB1 开头才算有效 —— 避免把错误响应当成果而提前返回。
     */
    private fun tryTransports(tag: Tag, cmd: ByteArray): ByteArray? {
        val a = NfcA.get(tag)
        if (a == null) {
            appendLog("✗ 标签不支持 NfcA，无法发送 0xB1 指令")
            return null
        }

        repeat(TRANSPORT_RETRIES) { attempt ->
            try {
                a.connect()
                a.timeout = 2000
                if (attempt == 0) {
                    appendLog(
                        "[NfcA] ATQA=${Crypto.bytesToHex(a.atqa)} SAK=${a.sak}" +
                            " 最大收发=${a.maxTransceiveLength}B"
                    )
                }
                val resp = a.transceive(cmd)
                appendLog("[NfcA] ← 响应(${resp.size}B): ${Crypto.bytesToHex(resp)}")
                if (resp.size >= 3 && resp[0] == 0xB1.toByte()) return resp
                appendLog("[NfcA] 响应不是合法的 0xB1 帧，重试 (${attempt + 1}/$TRANSPORT_RETRIES)")
            } catch (e: Exception) {
                appendLog("[NfcA] 第 ${attempt + 1} 次失败: ${e.message}")
            } finally {
                runCatching { a.close() }
            }
        }

        appendLog("✗ NfcA 通道重试 $TRANSPORT_RETRIES 次仍未获得合法响应")
        return null
    }
}

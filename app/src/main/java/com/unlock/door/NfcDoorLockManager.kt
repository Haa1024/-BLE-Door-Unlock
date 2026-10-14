package com.unlock.door

import android.nfc.Tag
import android.nfc.tech.IsoDep
import android.nfc.tech.MifareClassic
import android.nfc.tech.MifareUltralight
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
     * UID 诊断: 住理门锁感应区 UID 常以 ASCII "XN-" 开头 (与 BLE 广播名 XN-<设备ID> 同源),
     * 剩余 4 字节可能直接编码设备ID → 打印大端/小端两种解读并与配置对比, 供日志分析
     */
    private fun diagnoseUid(uid: ByteArray) {
        val ascii = uid.joinToString("") {
            val c = it.toInt() and 0xFF
            if (c in 0x20..0x7E) c.toChar().toString() else "·"
        }
        appendLog("UID ASCII: \"$ascii\"")
        if (uid.size >= 7 && uid[0] == 'X'.code.toByte() &&
            uid[1] == 'N'.code.toByte() && uid[2] == '-'.code.toByte()
        ) {
            appendLog("UID 含 XN- 前缀 → 确认是住理门锁感应区")
            val cfgId = if (SettingsManager.isInitialized()) SettingsManager.deviceId else 0
            if (cfgId > 0) {
                val be = ((uid[3].toInt() and 0xFF) shl 24) or ((uid[4].toInt() and 0xFF) shl 16) or
                        ((uid[5].toInt() and 0xFF) shl 8) or (uid[6].toInt() and 0xFF)
                val le = (uid[3].toInt() and 0xFF) or ((uid[4].toInt() and 0xFF) shl 8) or
                        ((uid[5].toInt() and 0xFF) shl 16) or ((uid[6].toInt() and 0xFF) shl 24)
                val match = when {
                    be == cfgId -> "大端匹配 ✓"
                    le == cfgId -> "小端匹配 ✓"
                    else -> "与配置不一致 (以配置为准)"
                }
                appendLog("UID 内嵌数值: 大端=$be 小端=$le (当前配置=$cfgId, $match)")
            }
        }
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

    // ─── 传输通道试探 ───

    private fun tryTransports(tag: Tag, cmd: ByteArray): ByteArray? {
        // 通道 1: IsoDep — 官方 20 字节帧风格与 ISO-DEP 最为契合, 首选
        IsoDep.get(tag)?.let { iso ->
            try {
                iso.connect()
                iso.timeout = 3000
                appendLog("[通道1] IsoDep 连接 (hiLayer=${Crypto.bytesToHex(iso.hiLayerResponse ?: ByteArray(0))})")
                var resp = iso.transceive(cmd)
                appendLog("[通道1] ← 原始响应: ${Crypto.bytesToHex(resp)}")
                resp = stripStatusWords(resp)
                if (resp.isNotEmpty()) return resp
            } catch (e: Exception) {
                appendLog("[通道1] IsoDep 失败: ${e.message}")
            } finally {
                runCatching { iso.close() }
            }
        } ?: appendLog("[通道1] 无 IsoDep 技术")

        // 通道 2: NfcA 原始直传 (Type A 低层)
        NfcA.get(tag)?.let { a ->
            try {
                a.connect()
                a.timeout = 3000
                appendLog("[通道2] NfcA ATQA=${Crypto.bytesToHex(a.atqa)} SAK=${a.sak} 最大收发=${a.maxTransceiveLength}B")
                val resp = a.transceive(cmd)
                appendLog("[通道2] ← 响应: ${Crypto.bytesToHex(resp)}")
                if (resp.isNotEmpty()) return resp
            } catch (e: Exception) {
                appendLog("[通道2] NfcA 失败: ${e.message}")
            } finally {
                runCatching { a.close() }
            }
        }

        // 通道 3: MifareUltralight — 页面信息转储 (为后续适配收集情报)
        MifareUltralight.get(tag)?.let { ul ->
            try {
                ul.connect()
                ul.timeout = 2000
                appendLog("[通道3] MifareUltralight type=${ul.type}")
                for (page in 0..15) {
                    runCatching {
                        val data = ul.readPages(page)
                        appendLog("[通道3] page$page: ${Crypto.bytesToHex(data)}")
                    }
                }
            } catch (e: Exception) {
                appendLog("[通道3] MifareUltralight 失败: ${e.message}")
            } finally {
                runCatching { ul.close() }
            }
        }

        // 通道 4: MifareClassic — 信息转储
        MifareClassic.get(tag)?.let { mc ->
            try {
                mc.connect()
                appendLog("[通道4] MifareClassic size=${mc.size} sectors=${mc.sectorCount}")
                runCatching {
                    if (mc.authenticateSectorWithKeyA(1, MifareClassic.KEY_DEFAULT)) {
                        val blk = mc.readBlock(mc.sectorToBlock(1))
                        appendLog("[通道4] sector1/block0: ${Crypto.bytesToHex(blk)}")
                    } else {
                        appendLog("[通道4] sector1 默认密钥认证失败 (非默认密钥)")
                    }
                }
            } catch (e: Exception) {
                appendLog("[通道4] MifareClassic 失败: ${e.message}")
            } finally {
                runCatching { mc.close() }
            }
        }

        return null
    }

    /** 剥离 ISO-DEP 响应尾部的状态字 SW1SW2 (9000/61xx/6xxx/63xx) */
    private fun stripStatusWords(resp: ByteArray): ByteArray {
        if (resp.size < 2) return resp
        val sw1 = resp[resp.size - 2].toInt() and 0xFF
        val sw2 = resp[resp.size - 1].toInt() and 0xFF
        val isSw = (sw1 == 0x90 && sw2 == 0x00) || sw1 == 0x61 ||
            (sw1 and 0xF0) == 0x60 || sw1 == 0x63
        if (isSw) {
            appendLog("  (剥离状态字 SW=${"%02X".format(sw1)}${"%02X".format(sw2)})")
            return resp.copyOfRange(0, resp.size - 2)
        }
        return resp
    }
}

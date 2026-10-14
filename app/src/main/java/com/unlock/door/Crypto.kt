package com.unlock.door

/**
 * 住理生活 BLE 开门 - 加密算法
 * 与 H5 前端源码完全一致
 */
object Crypto {

    // ─── 常量 ───
    private val FIXED_KEY = byteArrayOf(
        0xAC.toByte(), 0xAB.toByte(), 0xBC.toByte(), 0xDA.toByte(),
        0xAE.toByte(), 0xBF.toByte(), 0x14.toByte(), 0x26.toByte(),
        0x35.toByte(), 0x42.toByte(), 0x54.toByte(), 0x65.toByte(),
        0x72.toByte(), 0x87.toByte(), 0x92.toByte(), 0x01.toByte()
    )

    // BLE 服务 UUID
    const val SERVICE_UUID = "0000ff12-0000-1000-8000-00805f9b34fb"
    const val WRITE_UUID = "0000ff01-0000-1000-8000-00805f9b34fb"
    const val READ_UUID = "0000ff02-0000-1000-8000-00805f9b34fb"

    // 命令码
    const val CMD_CERT_HEAD = 0x74
    const val CMD_CERT_PACK = 0x75
    const val CMD_RECHAIN_REQ = 0x76
    const val CMD_PACK_READ = 0x77
    const val CMD_UNLOCK = 0x78

    // 响应码
    const val RESP_CERT_HEAD = 116
    const val RESP_CERT_PACK = 117
    const val RESP_RECHAIN_KEY = 118
    const val RESP_PACK_READ = 119
    const val RESP_CHAIN_KEY = 120

    // 错误码
    const val ERR_SUCCESS = 0
    const val ERR_NEED_UPDATE_KEY = 27  // 0x1B
    const val ERR_DOOR_ALREADY_OPEN = 23 // 0x17
    const val ERR_EXPIRED_BUT_OPEN = 24  // 0x18 — 门开了但凭证标记过期

    // CRC8 表 (多项式 0x07)
    val CRC8_TABLE = intArrayOf(
        0,94,188,226,97,63,221,131,194,156,126,32,163,253,31,65,
        157,195,33,127,252,162,64,30,95,1,227,189,62,96,130,220,
        35,125,159,193,66,28,254,160,225,191,93,3,128,222,60,98,
        190,224,2,92,223,129,99,61,124,34,192,158,29,67,161,255,
        70,24,250,164,39,121,155,197,132,218,56,102,229,187,89,7,
        219,133,103,57,186,228,6,88,25,71,165,251,120,38,196,154,
        101,59,217,135,4,90,184,230,167,249,27,69,198,152,122,36,
        248,166,68,26,153,199,37,123,58,100,134,216,91,5,231,185,
        140,210,48,110,237,179,81,15,78,16,242,172,47,113,147,205,
        17,79,173,243,112,46,204,146,211,141,111,49,178,236,14,80,
        175,241,19,77,206,144,114,44,109,51,209,143,12,82,176,238,
        50,108,142,208,83,13,239,177,240,174,76,18,145,207,45,115,
        202,148,118,40,171,245,23,73,8,86,180,234,105,55,213,139,
        87,9,235,181,54,104,138,212,149,203,41,119,244,170,72,22,
        233,183,85,11,136,214,52,106,43,117,151,201,74,20,246,168,
        116,42,200,150,21,75,169,247,182,232,10,84,215,137,107,53
    )

    // ─── CRC8 ───
    fun crc8(data: ByteArray): Int {
        var result = 0
        for (b in data) {
            result = CRC8_TABLE[result xor (b.toInt() and 0xFF)]
        }
        return result
    }

    fun crc8(data: ByteArray, offset: Int, length: Int): Int {
        var result = 0
        for (i in offset until offset + length) {
            result = CRC8_TABLE[result xor (data[i].toInt() and 0xFF)]
        }
        return result
    }

    // ─── 设备密钥派生 (与 JS 源码完全一致) ───
    fun deriveDeviceKey(deviceId: Int): ByteArray {
        // Step 1: deviceId 编码为 4 字节 LE
        val arr = java.nio.ByteBuffer.allocate(4)
            .order(java.nio.ByteOrder.LITTLE_ENDIAN).putInt(deviceId).array()

        // Step 2: 将 LE 字节按 BE 读取，得到 uint32 值 a
        val a = ((arr[0].toInt() and 0xFF) shl 24) or
                ((arr[1].toInt() and 0xFF) shl 16) or
                ((arr[2].toInt() and 0xFF) shl 8) or
                (arr[3].toInt() and 0xFF)

        // Step 3: FIXED_KEY 按 LE 读取为 4 个 uint32
        val n = IntArray(4)
        val buf = java.nio.ByteBuffer.wrap(FIXED_KEY)
            .order(java.nio.ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until 4) n[i] = buf.getInt()

        // Step 4: 变换 (所有运算用 Long 模拟 JS 的 uint32 >>> 0 语义)
        for (i in 0 until 4) {
            // e = (2654435769 + 305419896 * i) >>> 0
            val e = ((2654435769L + 305419896L * i) and 0xFFFFFFFFL).toInt()

            // t = ((a & e) + i >>> 0) + ((a | e) - 2*i >>> 0) - ((~a ^ e) >>> 0) >>> 0
            val t1 = (a and e).toLong() and 0xFFFFFFFFL
            val t2 = (a or e).toLong() and 0xFFFFFFFFL
            val t3 = (a.inv() xor e).toLong() and 0xFFFFFFFFL

            val t = ((t1 + i) + (t2 - 2L * i) - t3) and 0xFFFFFFFFL
            n[i] = n[i] xor t.toInt()
        }

        // Step 5: 结果按 BE 写入
        val out = java.nio.ByteBuffer.allocate(16)
            .order(java.nio.ByteOrder.BIG_ENDIAN)
        for (v in n) out.putInt(v)
        return out.array()
    }

    // ─── RC4 ───
    fun rc4(data: ByteArray, key: ByteArray): ByteArray {
        val s = IntArray(256) { it }
        var j = 0
        for (i in 0 until 256) {
            j = (j + s[i] + (key[i % key.size].toInt() and 0xFF)) % 256
            val tmp = s[i]; s[i] = s[j]; s[j] = tmp
        }

        val result = ByteArray(data.size)
        var i = 0; j = 0
        for (k in data.indices) {
            i = (i + 1) % 256
            j = (j + s[i]) % 256
            val tmp = s[i]; s[i] = s[j]; s[j] = tmp
            result[k] = (data[k].toInt() xor s[(s[i] + s[j]) % 256]).toByte()
        }
        return result
    }

    // ─── 十六进制工具 ───
    fun hexToBytes(hex: String): ByteArray {
        val h = hex.replace(" ", "").replace("\n", "").replace("0x", "")
        require(h.length % 2 == 0) { "hex长度必须为偶数" }
        return ByteArray(h.length / 2) { h.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }

    fun bytesToHex(bytes: ByteArray): String {
        return bytes.joinToString("") { "%02X".format(it) }
    }

    fun intToLe4(value: Int): ByteArray {
        return byteArrayOf(
            (value and 0xFF).toByte(),
            ((value shr 8) and 0xFF).toByte(),
            ((value shr 16) and 0xFF).toByte(),
            ((value shr 24) and 0xFF).toByte()
        )
    }

    // ─── 数据包构造 ───
    /**
     * 构造 20 字节 BLE 数据包
     * [0x14(1B)] [0x00(1B)] [cmd(1B)] [加密数据(16B)] [CRC8(1B)]
     *
     * 注意: CRC8 是对原始(未加密)数据计算的! (与 JS 源码一致)
     */
    fun buildPacket(cmd: Int, encryptedData: ByteArray, originalForCrc: ByteArray): ByteArray {
        val packet = ByteArray(20)
        packet[0] = 20
        packet[1] = 0
        packet[2] = cmd.toByte()

        // 填充加密数据 (最多 16 字节)
        for (i in 0 until minOf(16, encryptedData.size)) {
            packet[3 + i] = encryptedData[i]
        }

        // CRC8 对原始数据计算 (不是加密后的!)
        val payloadCrc = crc8(originalForCrc)
        packet[19] = payloadCrc.toByte()

        return packet
    }

    // ─── 开门协议数据包 ───

    /** Step 1: 凭证头 (0x74) — 发送元数据头部 */
    fun buildCredentialHead(chainKeyHex: String, deviceId: Int, projectId: Int): ByteArray {
        val chainKey = hexToBytes(chainKeyHex)
        val projectIdBytes = intToLe4(projectId)
        val derivedKey = deriveDeviceKey(deviceId)

        // 头部格式: [totalLen(2B LE)] [packCount(1B)] [crc8(1B)] [zeros(12B)]
        val totalLen = chainKey.size + 4 + projectIdBytes.size  // 32 + 4 + 4 = 40
        val packCount = Math.ceil(totalLen.toDouble() / 15).toInt()  // ceil(40/15) = 3
        val allData = projectIdBytes + chainKey  // 36 bytes
        val dataCrc = crc8(allData)  // CRC of full [projectId + chainKey]

        val header = ByteArray(16)
        header[0] = (totalLen and 0xFF).toByte()
        header[1] = ((totalLen shr 8) and 0xFF).toByte()
        header[2] = packCount.toByte()
        header[3] = dataCrc.toByte()
        // bytes 4-15 are already zero

        val encrypted = rc4(header, derivedKey)
        return buildPacket(CMD_CERT_HEAD, encrypted, header)
    }

    /** Step 2: 凭证分包 (0x75) */
    fun buildCredentialPacks(chainKeyHex: String, ran: Int, deviceId: Int, projectId: Int): List<ByteArray> {
        val chainKey = hexToBytes(chainKeyHex)
        val projectIdBytes = intToLe4(projectId)
        val ranBytes = intToLe4(ran)

        // payload = [ran(4B LE)] + [projectId(4B LE)] + [chainKey(32B)] = 40 bytes
        val payload = ranBytes + projectIdBytes + chainKey
        val derivedKey = deriveDeviceKey(deviceId)

        val packs = mutableListOf<ByteArray>()
        val chunkSize = 15

        var i = 0
        while (i < payload.size) {
            val end = minOf(i + chunkSize, payload.size)
            val chunk = payload.copyOfRange(i, end)

            // 分包数据: [pack_index(1B)] + [chunk] → 补零到 16 字节
            val packIndex = i / chunkSize
            val rawData = byteArrayOf(packIndex.toByte()) + chunk
            val packData = ByteArray(16)  // 预分配 16 字节，自动补零 (匹配 JS new Uint8Array(16))
            System.arraycopy(rawData, 0, packData, 0, rawData.size)

            // RC4 加密 (所有命令都用 deriveDeviceKey)
            val encrypted = rc4(packData, derivedKey)

            packs.add(buildPacket(CMD_CERT_PACK, encrypted, packData))
            i = end
        }
        return packs
    }

    /** Step 3: 开锁命令 (0x78) — RC4 加密 */
    fun buildUnlockCommand(deviceId: Int): ByteArray {
        val data = ByteArray(16) { 0 }
        val derivedKey = deriveDeviceKey(deviceId)
        val encrypted = rc4(data, derivedKey)
        return buildPacket(CMD_UNLOCK, encrypted, data)
    }

    /** 请求重链密钥 (0x76) — RC4 加密 */
    fun buildRechainKeyRequest(credentialId: Int, deviceId: Int): ByteArray {
        val data = ByteArray(16)
        val credBytes = intToLe4(credentialId)
        System.arraycopy(credBytes, 0, data, 0, 4)
        val derivedKey = deriveDeviceKey(deviceId)
        val encrypted = rc4(data, derivedKey)
        return buildPacket(CMD_RECHAIN_REQ, encrypted, data)
    }

    /** 读取密钥分包 (0x77) — RC4 加密 */
    fun buildPackReadRequest(packIndex: Int, deviceId: Int): ByteArray {
        val data = ByteArray(16)
        data[0] = packIndex.toByte()
        val derivedKey = deriveDeviceKey(deviceId)
        val encrypted = rc4(data, derivedKey)
        return buildPacket(CMD_PACK_READ, encrypted, data)
    }

    // ─── 响应解析 ───

    data class ParseResult(
        val success: Boolean,
        val cmd: Int = 0,
        val resultCode: Int = -1,
        val isSuccess: Boolean = false,
        val ran: Int = 0,
        val packCount: Int = 0,
        val totalLength: Int = 0,
        val crc8Val: Int = 0,
        val packIndex: Int = 0,
        val resultData: String = "",
        val seq: Int? = null,
        val doorAlreadyOpen: Boolean = false,
        val needUpdateKey: Boolean = false,
        val error: String? = null
    )

    fun parseResponse(hexData: String, deviceId: Int): ParseResult {
        val raw = try {
            hexToBytes(hexData)
        } catch (e: Exception) {
            return ParseResult(false, error = "hex解析失败: ${e.message}")
        }

        if (raw.size < 20) {
            return ParseResult(false, error = "数据长度错误: ${raw.size}")
        }

        val cmd = raw[2].toInt() and 0xFF
        val payload = raw.copyOfRange(3, 19)
        val expectedCrc = raw[19].toInt() and 0xFF

        // RC4 解密
        val derivedKey = deriveDeviceKey(deviceId)
        val decrypted = rc4(payload, derivedKey)

        // CRC 校验
        val actualCrc = crc8(decrypted)
        android.util.Log.d("Crypto", "deriveKey=${bytesToHex(derivedKey)}")
        android.util.Log.d("Crypto", "payload=${bytesToHex(payload)}")
        android.util.Log.d("Crypto", "decrypted=${bytesToHex(decrypted)}")
        android.util.Log.d("Crypto", "expectCRC=$expectedCrc actualCRC=$actualCrc")
        if (actualCrc != expectedCrc) {
            return ParseResult(false, error = "CRC校验失败: 期望$actualCrc, 实际$expectedCrc")
        }

        return when (cmd) {
            RESP_CERT_HEAD -> {
                val resultCode = decrypted[0].toInt() and 0xFF
                val ran = if (decrypted.size >= 8) {
                    (decrypted[4].toInt() and 0xFF) or
                    ((decrypted[5].toInt() and 0xFF) shl 8) or
                    ((decrypted[6].toInt() and 0xFF) shl 16) or
                    ((decrypted[7].toInt() and 0xFF) shl 24)
                } else 0
                ParseResult(true, cmd, resultCode, resultCode == ERR_SUCCESS, ran = ran)
            }

            RESP_CERT_PACK -> {
                ParseResult(true, cmd, 0, true)
            }

            RESP_RECHAIN_KEY -> {
                val resultCode = decrypted[0].toInt() and 0xFF
                val packCount = decrypted[1].toInt() and 0xFF
                val totalLen = (decrypted[2].toInt() and 0xFF) or ((decrypted[3].toInt() and 0xFF) shl 8)
                val crc = decrypted[4].toInt() and 0xFF
                val doorOpen = resultCode == ERR_DOOR_ALREADY_OPEN
                ParseResult(true, cmd, resultCode,
                    isSuccess = resultCode == ERR_SUCCESS || doorOpen,
                    packCount = packCount, totalLength = totalLen, crc8Val = crc,
                    doorAlreadyOpen = doorOpen)
            }

            RESP_PACK_READ -> {
                val packIndex = decrypted[0].toInt() and 0xFF
                val resultData = bytesToHex(decrypted.copyOfRange(1, 16))
                ParseResult(true, cmd, 0, true, packIndex = packIndex, resultData = resultData)
            }

            RESP_CHAIN_KEY -> {
                val resultCode = decrypted[0].toInt() and 0xFF
                val seq = if (decrypted.size >= 5) decrypted[4].toInt() and 0xFF else null
                // 24 (ERR_EXPIRED_BUT_OPEN) 不视为开门成功 — 需走服务器刷新凭证流程
                val doorOpen = resultCode == ERR_DOOR_ALREADY_OPEN
                ParseResult(true, cmd, resultCode,
                    isSuccess = resultCode == ERR_SUCCESS || doorOpen,
                    seq = seq, doorAlreadyOpen = doorOpen,
                    needUpdateKey = resultCode == ERR_NEED_UPDATE_KEY)
            }

            else -> ParseResult(true, cmd, decrypted[0].toInt() and 0xFF, false,
                error = "未知命令: 0x${cmd.toString(16).uppercase()}")
        }
    }

    /** 拼装多包 chainKey */
    fun assembleChainKey(packs: List<PackData>, expectedLength: Int, expectedCrc: Int): ByteArray {
        val sorted = packs.sortedBy { it.packIndex }
        val allHex = sorted.joinToString("") { it.resultData }
        val raw = hexToBytes(allHex)
        val trimmed = raw.copyOf(expectedLength)

        require(crc8(trimmed) == expectedCrc) { "chainKey CRC校验失败" }
        require(trimmed.size == expectedLength) { "chainKey长度不匹配: 期望$expectedLength, 实际${trimmed.size}" }

        return trimmed
    }

    data class PackData(val packIndex: Int, val resultData: String)

    // ═══ NFC 门锁协议 (0xB1) — 移植自官方 H5 nfcOpenDoorCommand.Z56WBVD_.js ═══
    // 官方架构: 门锁感应区模拟 NDEF 标签 (URL 含设备ID), 手机作为读卡器
    // 写入 0xB1 加密指令, 锁校验 projectId+chainKey 后返回新密钥 (密钥轮换)

    const val NFC_CMD_HEADER = 0xB1
    const val NFC_SUB_OPEN = 0x0D             // 开镜子命令 (帧内固定为 13)
    const val NFC_RESP_CMD_CRC_ERROR = 0xF0   // 锁回应: 数据CRC错误 (echo 子命令)
    const val NFC_RESP_CMD_UNKNOWN = 0xF1     // 锁回应: 未知命令 (echo 子命令)

    /** NFC 结果码表 (源自 hooks.js 第二张映射表) */
    val NFC_ERROR_MESSAGES = mapOf(
        0 to "成功", 1 to "设备已被占用", 2 to "数据CRC校验错误", 3 to "生成随机密钥失败",
        4 to "用户凭证保存失败", 5 to "开锁交换密钥失败", 6 to "解密随机密钥失败",
        7 to "未找到用户信息", 8 to "钥匙类型不匹配", 9 to "管理员随机数不匹配",
        10 to "门锁状态已打开", 11 to "已过有效期", 12 to "离线次数已用完",
        13 to "用户凭证更新失败", 14 to "用户已删除", 15 to "项目ID不一致",
        255 to "未知错误"
    )

    data class NfcParseResult(
        val success: Boolean,
        val resultCode: Int = -1,
        val message: String = "",
        val doorAlreadyOpen: Boolean = false,
        val newChainKeyHex: String? = null,
        val seq: Int? = null,
        val error: String? = null
    )

    /**
     * 构造 NFC 0xB1 开锁指令 (与 JS 端 b() 函数逐行对应)
     *
     * 帧格式: [0xB1][0x0D][密文长度(1B)][RC4密文][CRC8(1B)]
     *   - 明文 = projectId(4B LE) + chainKey(原始字节)
     *   - RC4 密钥 = deriveDeviceKey(deviceId)
     *   - CRC8 输入 = [0x0D, 密文长度, ...明文] (对明文而非密文计算, 与 BLE 一致)
     */
    fun buildNfcOpenCommand(deviceId: Int, projectId: Int, chainKeyHex: String): ByteArray {
        val chain = hexToBytes(chainKeyHex)
        val plain = intToLe4(projectId) + chain
        val cipher = rc4(plain, deriveDeviceKey(deviceId))

        val packet = ByteArray(3 + cipher.size + 1)
        packet[0] = NFC_CMD_HEADER.toByte()
        packet[1] = NFC_SUB_OPEN.toByte()
        packet[2] = cipher.size.toByte()
        System.arraycopy(cipher, 0, packet, 3, cipher.size)

        val crcInput = byteArrayOf(packet[1], packet[2]) + plain
        packet[packet.size - 1] = crc8(crcInput).toByte()
        return packet
    }

    /**
     * 解析 NFC 0xB1 响应 (与 JS 端 I() 函数逐行对应)
     *
     * 帧格式: [0xB1][子命令][密文长度][RC4密文][CRC8]
     *   - CRC8 输入 = [子命令, 密文长度, ...解密明文]
     *   - 明文布局: [0]=结果码, [1..3]=保留, [4..35]=新chainKey(32B), [36..39]=seq(4B LE)
     *   - 子命令 echo 0xF0=锁回报CRC错误 / 0xF1=锁回报未知命令
     */
    fun parseNfcResponse(raw: ByteArray, deviceId: Int): NfcParseResult {
        if (raw.isEmpty()) return NfcParseResult(false, error = "响应为空")

        // 帧头定位: 容忍传输层前缀干扰, 找 0xB1 开头的帧 (JS 为严格 raw[0]==0xB1)
        var f: ByteArray? = null
        for (start in 0..(raw.size - 4)) {
            if ((raw[start].toInt() and 0xFF) == NFC_CMD_HEADER) {
                f = raw.copyOfRange(start, raw.size); break
            }
        }
        val frame = f ?: return NfcParseResult(
            false, error = "响应不是NFC命令帧 (头8字节: ${bytesToHex(raw.copyOf(minOf(8, raw.size)))})"
        )

        val subCmd = frame[1].toInt() and 0xFF
        if (subCmd == NFC_RESP_CMD_CRC_ERROR) return NfcParseResult(false, error = "锁回报: 数据CRC错误")
        if (subCmd == NFC_RESP_CMD_UNKNOWN) return NfcParseResult(false, error = "锁回报: 未知命令")

        val cipherLen = frame[2].toInt() and 0xFF
        if (frame.size < 3 + cipherLen + 1) {
            return NfcParseResult(false, error = "响应长度不足: ${frame.size} < ${3 + cipherLen + 1}")
        }

        val cipher = frame.copyOfRange(3, 3 + cipherLen)
        val expectedCrc = frame[3 + cipherLen].toInt() and 0xFF
        val decrypted = rc4(cipher, deriveDeviceKey(deviceId))
        val actualCrc = crc8(byteArrayOf(frame[1], frame[2]) + decrypted)
        if (actualCrc != expectedCrc) {
            return NfcParseResult(false, error = "CRC校验失败: 期望$expectedCrc 实际$actualCrc")
        }

        val resultCode = decrypted[0].toInt() and 0xFF
        val doorAlreadyOpen = resultCode == 10  // "门锁状态已打开"
        val message = NFC_ERROR_MESSAGES[resultCode] ?: "未知结果码$resultCode"

        var newKey: String? = null
        var seq: Int? = null
        if (resultCode == 0 || doorAlreadyOpen) {
            // 全帧索引 [7..38] → 解密明文 [4..35] = 32字节新 chainKey
            if (decrypted.size >= 36) {
                newKey = bytesToHex(decrypted.copyOfRange(4, 36))
            }
            // 全帧索引 [39..42] → 解密明文 [36..39] = seq (uint32 LE)
            if (decrypted.size >= 40) {
                seq = (decrypted[36].toInt() and 0xFF) or
                      ((decrypted[37].toInt() and 0xFF) shl 8) or
                      ((decrypted[38].toInt() and 0xFF) shl 16) or
                      ((decrypted[39].toInt() and 0xFF) shl 24)
            }
        }

        return NfcParseResult(
            success = resultCode == 0 || doorAlreadyOpen,
            resultCode = resultCode,
            message = message,
            doorAlreadyOpen = doorAlreadyOpen,
            newChainKeyHex = newKey,
            seq = seq
        )
    }
}

package com.unlock.door

import android.app.Activity
import android.nfc.NdefMessage
import android.nfc.NdefRecord
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.Ndef
import android.nfc.tech.NdefFormatable

/**
 * NFC 标签读写工具 — 「碰标签开门」保底方案
 *
 * 场景: 门锁的 HCE 私有协议尚未逆向完成时，先享受 NFC 便利性:
 * 1. 设置页点「写入 NFC 标签」→ 手机贴近一张空白 NTAG 标签 → 写入开门指令
 * 2. 把标签贴在门边 (墙面/门框，不用贴锁上)
 * 3. 之后手机碰标签 → 系统唤起 App → 自动执行 BLE 开门，全程不用点屏幕
 *
 * 标签内容: URI 记录 [unlockdoor://open] + AAR 应用记录
 *   - URI 使系统以最高优先级 ACTION_NDEF_DISCOVERED 唤起本 App
 *   - AAR 兜底保证即使 URI 分发失败也必定唤起本 App
 *
 * 推荐标签: NTAG213 (144字节) / NTAG215 (504字节)，几块钱一张
 */
object NfcTagHelper {

    /** 开门指令 URI（与 AndroidManifest 中 MainActivity 的 intent-filter 对应） */
    const val UNLOCK_URI = "unlockdoor://open"

    fun isNfcAvailable(activity: Activity): Boolean =
        NfcAdapter.getDefaultAdapter(activity) != null

    fun isNfcEnabled(activity: Activity): Boolean =
        NfcAdapter.getDefaultAdapter(activity)?.isEnabled == true

    /** 构建写入标签的 NDEF 消息 */
    fun buildUnlockMessage(): NdefMessage {
        val uriRecord = NdefRecord.createUri(UNLOCK_URI)
        val aarRecord = NdefRecord.createApplicationRecord("com.unlock.door")
        return NdefMessage(arrayOf(uriRecord, aarRecord))
    }

    /**
     * 把开门指令写入 NFC 标签
     * @param tag ReaderMode 回调拿到的 Tag 对象
     * @return null = 写入成功；非 null = 错误信息
     */
    fun writeUnlockTag(tag: Tag): String? {
        val message = buildUnlockMessage()
        val payloadSize = message.toByteArray().size

        val ndef = Ndef.get(tag)
        if (ndef != null) {
            try {
                ndef.connect()
                if (!ndef.isWritable) return "标签被锁定为只读，请换一张新标签"
                if (ndef.maxSize < payloadSize) {
                    return "标签容量不足: 需要($payloadSize)字节, 标签仅${ndef.maxSize}字节。推荐 NTAG213/215"
                }
                ndef.writeNdefMessage(message)
                return null
            } catch (e: Exception) {
                return "写入失败: ${e.message} (请保持手机贴稳标签不要移开)"
            } finally {
                runCatching { ndef.close() }
            }
        }

        // 未格式化的标签 → 先格式化为 NDEF 再写入
        val formatable = NdefFormatable.get(tag)
            ?: return "该标签不支持 NDEF 数据格式 (不是 NTAG / MIFARE Ultralight 类标签)，请换卡"
        return try {
            formatable.connect()
            formatable.format(message)
            null
        } catch (e: Exception) {
            "格式化写入失败: ${e.message}"
        } finally {
            runCatching { formatable.close() }
        }
    }

}

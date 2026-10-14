package com.unlock.door

import android.app.Activity
import com.alipay.sdk.app.AuthTask
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 支付宝快捷登录 —— SDK 授权部分
 *
 * 链路（完全复刻官方 H5 登录页 public/js/phone.D9sxfyEG.js 的 sa() / oa()）：
 *   ① ServerApi.fetchAlipayAuthInfo()  取 authStr
 *   ② AlipayLogin.auth(...)            调起支付宝客户端，拿 auth_code
 *   ③ ServerApi.loginByAlipayAuthCode()  换 platform_token
 *
 * SDK 15.8.17 的 `AuthTask.authV2(authInfo, isShowLoading)` 是**阻塞式**方法，
 * 直接返回一个三键 Map：
 *   resultStatus —— "9000" 成功 / "6001" 中途取消 / "4000" 失败 / "8000" 处理中
 *   memo         —— 结果描述
 *   result       —— 原始串，形如
 *                   success=true&result_code=200&app_id=..&auth_code=..&scope=kuaijie&...
 *
 * 因此必须在 IO 线程调用；auth_code 要从 result 里自己解析（该版本 SDK 不带 AuthResult 类）。
 */
object AlipayLogin {

    private const val STATUS_SUCCESS = "9000"
    private const val STATUS_CANCEL = "6001"
    /** 8000 = 正在处理中，不是终态但也不会带回 auth_code */
    private const val STATUS_PENDING = "8000"

    private const val KEY_AUTH_CODE = "auth_code"

    /**
     * ② 调起支付宝做账号授权。
     * @return 支付宝返回的 auth_code
     * @throws Exception 用户取消 / 支付宝拒绝 / 未拿到授权码
     */
    suspend fun auth(activity: Activity, authInfo: String): String = withContext(Dispatchers.IO) {
        require(authInfo.isNotBlank()) { "授权串为空" }

        // 阻塞调用：内部会拉起支付宝 Activity 并等待其返回
        val map = AuthTask(activity).authV2(authInfo, true)

        val status = map["resultStatus"].orEmpty()
        val memo = map["memo"].orEmpty()
        val raw = map["result"].orEmpty()
        android.util.Log.d("AlipayLogin", "authV2 → status=$status memo=$memo result=$raw")

        when (status) {
            STATUS_SUCCESS -> {
                val code = parseAuthCode(raw)
                if (code.isBlank()) throw Exception("支付宝未返回授权码\n$raw")
                code
            }
            STATUS_CANCEL -> throw Exception("已取消支付宝授权")
            STATUS_PENDING -> throw Exception("支付宝正在处理中，请稍后重试")
            else -> throw Exception("支付宝授权失败($status) ${memo.ifBlank { raw }}")
        }
    }

    /** result 形如 success=true&result_code=200&app_id=..&auth_code=..&scope=kuaijie&... */
    private fun parseAuthCode(raw: String): String {
        for (pair in raw.split("&")) {
            val i = pair.indexOf('=')
            if (i > 0 && pair.substring(0, i) == KEY_AUTH_CODE) {
                return runCatching {
                    java.net.URLDecoder.decode(pair.substring(i + 1), "UTF-8")
                }.getOrElse { pair.substring(i + 1) }
            }
        }
        return ""
    }
}

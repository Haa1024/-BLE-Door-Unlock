package com.unlock.door

import android.content.Context
import android.content.SharedPreferences

/**
 * 凭证参数持久化存储
 * 所有人填入自己的门锁参数即可使用，无需修改代码
 */
object SettingsManager {

    private const val PREFS_NAME = "door_unlock_settings"

    private const val KEY_DEVICE_ID = "device_id"
    private const val KEY_PROJECT_ID = "project_id"
    private const val KEY_CREDENTIAL_ID = "credential_id"
    private const val KEY_CHAIN_KEY = "chain_key"
    private const val KEY_DEVICE_MAC = "device_mac"

    // 默认值（空值，用户需在设置页填入自己的数据）
    private const val DEF_DEVICE_ID = 0
    private const val DEF_PROJECT_ID = 0
    private const val DEF_CREDENTIAL_ID = 0
    private const val DEF_CHAIN_KEY = ""
    private const val DEF_DEVICE_MAC = ""

    private lateinit var prefs: SharedPreferences

    fun init(context: Context) {
        prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun isInitialized(): Boolean = ::prefs.isInitialized

    var deviceId: Int
        get() = prefs.getInt(KEY_DEVICE_ID, DEF_DEVICE_ID)
        set(v) = prefs.edit().putInt(KEY_DEVICE_ID, v).apply()

    var projectId: Int
        get() = prefs.getInt(KEY_PROJECT_ID, DEF_PROJECT_ID)
        set(v) = prefs.edit().putInt(KEY_PROJECT_ID, v).apply()

    var credentialId: Int
        get() = prefs.getInt(KEY_CREDENTIAL_ID, DEF_CREDENTIAL_ID)
        set(v) = prefs.edit().putInt(KEY_CREDENTIAL_ID, v).apply()

    var chainKey: String
        get() = prefs.getString(KEY_CHAIN_KEY, DEF_CHAIN_KEY) ?: DEF_CHAIN_KEY
        set(v) = prefs.edit().putString(KEY_CHAIN_KEY, v).apply()

    var deviceMac: String
        get() = prefs.getString(KEY_DEVICE_MAC, DEF_DEVICE_MAC) ?: DEF_DEVICE_MAC
        set(v) = prefs.edit().putString(KEY_DEVICE_MAC, v).apply()

    /** BLE 扫描名称 */
    fun bleName(): String = "XN-$deviceId"

    /** chainKey 是否已填写 */
    fun isConfigured(): Boolean = chainKey.isNotBlank() && chainKey.length == 64

    // ─── 免责条款 ───
    private const val KEY_AGREED = "agreed_disclaimer"

    var agreedDisclaimer: Boolean
        get() = prefs.getBoolean(KEY_AGREED, false)
        set(v) = prefs.edit().putBoolean(KEY_AGREED, v).apply()

    // ─── 日志开关 ───
    private const val KEY_SHOW_LOGS = "show_logs"

    var showLogs: Boolean
        get() = prefs.getBoolean(KEY_SHOW_LOGS, false)  // 默认关闭
        set(v) = prefs.edit().putBoolean(KEY_SHOW_LOGS, v).apply()

    // ─── 登录凭证（可选，用于在线开门重置离线计数器） ───
    private const val KEY_LOGIN_PHONE = "login_phone"
    private const val KEY_LOGIN_PASSWORD = "login_password"
    private const val KEY_PLATFORM_TOKEN = "platform_token"
    private const val KEY_SERVER_URL = "server_url"

    var loginPhone: String
        get() = prefs.getString(KEY_LOGIN_PHONE, "") ?: ""
        set(v) = prefs.edit().putString(KEY_LOGIN_PHONE, v).apply()

    var loginPassword: String
        get() = prefs.getString(KEY_LOGIN_PASSWORD, "") ?: ""
        set(v) = prefs.edit().putString(KEY_LOGIN_PASSWORD, v).apply()

    var platformToken: String
        get() = prefs.getString(KEY_PLATFORM_TOKEN, "") ?: ""
        set(v) = prefs.edit().putString(KEY_PLATFORM_TOKEN, v).apply()

    val serverBaseUrl: String
        get() = "https://pm.whxinna.com"

    var projectServerUrl: String
        get() = prefs.getString("project_server_url", "") ?: ""
        set(v) = prefs.edit().putString("project_server_url", v).apply()

    var projectSignKey: String
        get() = prefs.getString("project_sign_key", "") ?: ""
        set(v) = prefs.edit().putString("project_sign_key", v).apply()

    var projectSessionKey: String
        get() = prefs.getString("project_session_key", "") ?: ""
        set(v) = prefs.edit().putString("project_session_key", v).apply()

    // ★ 项目客户端 gt 签名需要的额外参数
    var userId: String
        get() = prefs.getString("user_id", "") ?: ""
        set(v) = prefs.edit().putString("user_id", v).apply()

    var serverAppId: String
        get() = prefs.getString("server_appid", "") ?: ""
        set(v) = prefs.edit().putString("server_appid", v).apply()

    var serverId: String
        get() = prefs.getString("server_id", "") ?: ""
        set(v) = prefs.edit().putString("server_id", v).apply()

    var identityCode: String
        get() = prefs.getString("identity_code", "") ?: ""
        set(v) = prefs.edit().putString("identity_code", v).apply()

    /** 是否已登录（有 token 即可调在线 API） */
    fun isLoggedIn(): Boolean = platformToken.isNotBlank()
}

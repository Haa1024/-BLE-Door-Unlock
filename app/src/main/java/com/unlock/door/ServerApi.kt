package com.unlock.door

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder

/**
 * 住理生活服务器 API
 * 用于登录获取 token、在线创建开门命令（重置离线计数器）
 */
object ServerApi {

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    private val FORM_TYPE = "application/x-www-form-urlencoded".toMediaType()

    // 签名密钥 (从原 App 提取, 所有 API 请求共用)
    private const val SIGN_KEY = "6d5dbb85b949447a95ff8fda9a9b759b"

    /** API 服务器: 项目专属服务器，登录时自动获取，未登录则用 pm */
    private fun apiServer(): String =
        SettingsManager.projectServerUrl.ifBlank { "https://pm.whxinna.com" }

    private fun generateTimestamp(): String = (System.currentTimeMillis() / 1000).toString()

    private fun generateNonce(): String {
        val chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
        return (1..32).map { chars.random() }.joinToString("")
    }

    /** 签名算法: MD5, 可指定密钥 */
    private fun sign(params: Map<String, String>, key: String = SIGN_KEY): String {
        val sorted = params.toSortedMap()
        val query = sorted.entries
            .filter { it.value.isNotEmpty() }
            .joinToString("&") {
                val v = org.json.JSONObject.quote(it.value).replace("\"", "").replace(" ", "")
                "${it.key}=$v"
            }
        val raw = "$query&key=$key"
        android.util.Log.d("ServerApi", "签名原文: $raw")
        val md5 = java.security.MessageDigest.getInstance("MD5")
            .digest(raw.toByteArray())
        return md5.joinToString("") { "%02X".format(it) }
    }

    /** 解码 JS encode: btoa(encodeURIComponent(str)).replace(/\+/g, "-").replace(/\//g, "_") */
    private fun decodeBase64Data(data: String): String {
        val fixed = data.replace("-", "+").replace("_", "/")
        val decoded = android.util.Base64.decode(fixed, android.util.Base64.DEFAULT)
        return String(decoded, Charsets.UTF_8)
    }

    /** 解析 server_info 对象 */
    private fun parseServerInfo(info: JSONObject) {
        val srv = info.optString("server_addr", "")
        if (srv.isNotBlank()) SettingsManager.projectServerUrl = srv
        val secret = info.optString("appsecret", "")
        if (secret.isNotBlank()) SettingsManager.projectSignKey = secret
        val sessionSecret = info.optString("session_secret", "")
        if (sessionSecret.isNotBlank()) SettingsManager.projectSessionKey = sessionSecret
        val serverAppId = info.optString("server_appid", "")
        if (serverAppId.isNotBlank()) SettingsManager.serverAppId = serverAppId
        val serverId = info.optString("server_id", "")
        if (serverId.isNotBlank()) SettingsManager.serverId = serverId
    }

    /** gt 客户端签名: 自动注入 user_id/pid/appid/identitycode */
    private fun signGtRequest(
        vararg pairs: Pair<String, String>,
    ): Map<String, String> {
        val key = SettingsManager.projectSessionKey.ifBlank {
            SettingsManager.projectSignKey.ifBlank { SIGN_KEY }
        }
        val params = mutableMapOf(*pairs)
        val uid = SettingsManager.userId
        if (uid.isNotBlank()) params["user_id"] = uid
        val pid = SettingsManager.serverAppId
        if (pid.isNotBlank()) params["pid"] = pid
        val appid = SettingsManager.serverId
        if (appid.isNotBlank()) params["appid"] = appid
        val idCode = SettingsManager.identityCode
        if (idCode.isNotBlank()) params["identitycode"] = idCode
        params["timestamp"] = generateTimestamp()
        params["noncestr"] = generateNonce()
        params["sign"] = sign(params, key)
        return params
    }

    /** 给请求参数加上 timestamp / nonce / sign, 可指定密钥 */
    private fun signRequest(
        vararg pairs: Pair<String, String>,
        key: String = SIGN_KEY,
    ): Map<String, String> {
        val params = mutableMapOf(*pairs)
        params["timestamp"] = generateTimestamp()
        params["noncestr"] = generateNonce()
        params["sign"] = sign(params, key)
        return params
    }

    // ─── 发送验证码 ───

    data class CodeResult(
        val success: Boolean,
        val error: String = "",
    )

    suspend fun sendSmsCode(phone: String): CodeResult = withContext(Dispatchers.IO) {
        try {
            val url = "${apiServer()}/webapi/users/get_login_code?phone=$phone"
            android.util.Log.d("ServerApi", "发送验证码: $url")

            val request = Request.Builder().url(url).get().build()
            val response = client.newCall(request).execute()
            val bodyStr = response.body?.string() ?: ""
            android.util.Log.d("ServerApi", "验证码响应 code=${response.code} body=${bodyStr.take(200)}")

            if (response.isSuccessful) {
                CodeResult(true)
            } else {
                val json = try { JSONObject(bodyStr) } catch (_: Exception) { JSONObject() }
                CodeResult(false, json.optString("err_msg", "发送失败 (${response.code})"))
            }
        } catch (e: Exception) {
            CodeResult(false, "${e.javaClass.simpleName}: ${e.message}")
        }
    }

    // ─── 登录 ───

    data class LoginResult(
        val success: Boolean,
        val token: String = "",
        val projectName: String = "",
        val error: String = "",
    )

    suspend fun login(phone: String, password: String): LoginResult = withContext(Dispatchers.IO) {
        try {
            // GET /webapi/users/login (原 App 实际走这个 — cA = Ve = je.get("/webapi/users/login"))
            val signed = signRequest(
                "phone" to phone,
                "pwd" to password,
            )
            val query = signed.entries.joinToString("&") { "${it.key}=${it.value}" }

            // 试所有服务器
            val servers = listOf(
                "https://pm.whxinna.com",
                "https://m5-zhuli.whxinna.com",
                "https://p4-zhuli.whxinna.com",
                "https://f5-zhuli.whxinna.com",
            )
            val results = mutableListOf<String>()
            for (srv in servers) {
                val url = "$srv/webapi/users/login?$query"
                val request = Request.Builder().url(url).get().build()
                val response = client.newCall(request).execute()
                val bodyStr = response.body?.string() ?: ""
                results.add("$srv → ${response.code} ${bodyStr.take(120)}")
                if (response.code == 404 || bodyStr.isBlank()) continue
                val json = try { JSONObject(bodyStr) } catch (_: Exception) { continue }
                if (!json.optBoolean("result", false)) continue

                // 1. 先查顶层 platform_token
                var token = json.optString("platform_token", "")
                if (token.isBlank()) token = json.optJSONObject("data")?.optString("platform_token", "") ?: ""

                // 打印顶层 JSON 字段 (调试)
                android.util.Log.d("ServerApi", "login 顶层字段: ${json.keys().asSequence().toList()}")
                android.util.Log.d("ServerApi", "login 完整响应: ${bodyStr.take(800)}")

                // 2. 解析 data (可能是 base64 字符串, 也可能是 JSON 对象)
                var dataJson: JSONObject? = json.optJSONObject("data")
                if (dataJson == null) {
                    val dataB64 = json.optString("data", "")
                    if (dataB64.isNotBlank()) {
                        val decoded = decodeBase64Data(dataB64)
                        dataJson = try { JSONObject(decoded) } catch (_: Exception) { null }
                    }
                }
                if (dataJson != null) {
                    if (token.isBlank()) token = dataJson.optString("platform_token", "")
                    // 保存用户 ID 和 identity_code (项目服务器会话令牌)
                    val userInfo = dataJson.optJSONObject("user_info")
                    android.util.Log.d("ServerApi", "userInfo keys: ${userInfo?.keys()?.asSequence()?.toList()}")
                    val uid = userInfo?.optString("id", "") ?: ""
                    val idCode = userInfo?.optString("identity_code", "") ?: ""
                    android.util.Log.d("ServerApi", "uid='$uid' idCode='${idCode.take(30)}...'")
                    if (uid.isNotBlank()) {
                        SettingsManager.userId = uid
                        android.util.Log.d("ServerApi", "已保存 userId=$uid")
                    } else {
                        android.util.Log.d("ServerApi", "userId 为空!")
                    }
                    if (idCode.isNotBlank()) {
                        SettingsManager.identityCode = idCode
                    } else {
                        // 兜底: 直接在顶层的 id / user_id / staff_id
                        val fallbackUid = dataJson.optString("id", "").ifBlank {
                            dataJson.optString("user_id", "").ifBlank {
                                dataJson.optString("staff_id", "")
                            }
                        }
                        if (fallbackUid.isNotBlank()) SettingsManager.userId = fallbackUid
                    }
                    val info = dataJson.optJSONObject("server_info")
                    if (info != null) {
                        parseServerInfo(info)
                    }
                }

                // 3. 顶层 server_info
                val topInfo = json.optJSONObject("server_info")
                if (topInfo != null) parseServerInfo(topInfo)
                val topSrv = json.optString("server_addr", "")
                if (topSrv.isNotBlank()) SettingsManager.projectServerUrl = topSrv

                // 4. 尝试从顶层 user_info 提取 user_id (如果 data 用 JSON 对象非 base64)
                if (SettingsManager.userId.isBlank()) {
                    val topUserInfo = json.optJSONObject("user_info")
                    val topUid = topUserInfo?.optString("id", "") ?: json.optString("id", "").ifBlank {
                        json.optString("user_id", "").ifBlank {
                            json.optString("staff_id", "")
                        }
                    }
                    if (topUid.isNotBlank()) SettingsManager.userId = topUid
                }

                if (token.isNotBlank()) {
                    SettingsManager.platformToken = token
                    // 把完整响应保存供调试
                    val saved = "token前20=${token.take(20)}... | 服务器=${SettingsManager.projectServerUrl.ifBlank{"无"}} | 完整=${bodyStr.take(500)}"
                    return@withContext LoginResult(true, token, saved)
                }
            }
            return@withContext LoginResult(false, error = results.joinToString("\n"))
        } catch (e: Exception) {
            LoginResult(false, error = "${e.javaClass.simpleName}: ${e.message}")
        }
    }

    // ─── 在线开门命令 ───

    data class CreateResult(
        val success: Boolean,
        val payload: List<String> = emptyList(), // hex 包列表, 逗号分隔的原始响应
        val rawPayload: String = "",             // 原始逗号分隔字符串, 用于回传 parse
        val responseData: String = "",           // 原始响应 JSON, 用于 parse 请求
        val error: String = "",
    )

    suspend fun createCommand(
        deviceId: Int,
        credentialId: Int,
    ): CreateResult = withContext(Dispatchers.IO) {
        try {
            // ★ 项目级 API，用 gt 客户端签名方式
            val srv = SettingsManager.projectServerUrl.ifBlank { "https://pm.whxinna.com" }
            val key = SettingsManager.projectSessionKey.ifBlank {
                SettingsManager.projectSignKey.ifBlank { SIGN_KEY }
            }
            val signed = signGtRequest(
                "device_id" to deviceId.toString(),
                "command" to "1",
                "credential_id" to credentialId.toString(),
                "type" to "ble",
            )
            val query = signed.entries.joinToString("&") { (k, v) ->
                "${k}=${URLEncoder.encode(v, "UTF-8")}"
            }
            val url = "$srv/webapi/v1/door_lock/command/create?$query"

            val request = Request.Builder().url(url).get().build()
            val response = client.newCall(request).execute()
            val bodyStr = response.body?.string() ?: ""
            val json = JSONObject(bodyStr)

            if (response.isSuccessful && json.optBoolean("result", false)) {
                val data = json.optJSONObject("data")
                val rawPayload = data?.optString("payload", "") ?: ""
                val packets = if (rawPayload.isNotBlank()) rawPayload.split(",") else emptyList()
                CreateResult(true, packets, rawPayload, bodyStr)
            } else {
                val msg = json.optString("err_msg", "").ifBlank {
                    json.optString("msg", "创建命令失败 (${response.code})")
                }
                CreateResult(false, error = msg)
            }
        } catch (e: IOException) {
            CreateResult(false, error = "网络错误: ${e.message}")
        }
    }

    data class ParseResult(
        val success: Boolean,
        val chainKey: String = "",
        val credentialId: Int = 0,
        val error: String = "",
    )

    suspend fun parseCommand(
        requestData: String,
        responsesPayload: String,
    ): ParseResult = withContext(Dispatchers.IO) {
        try {
            // ★ 项目级 API，用 gt 客户端签名方式
            val srv = SettingsManager.projectServerUrl.ifBlank { "https://pm.whxinna.com" }
            val origJson = JSONObject(requestData)
            val origData = origJson.optJSONObject("data") ?: origJson
            val signed = signGtRequest(
                "device_id" to origData.optInt("device_id", 0).toString(),
                "command" to origData.optInt("command", 1).toString(),
                "credential_id" to origData.optInt("credential_id", 0).toString(),
                "type" to "ble",
                "payload" to responsesPayload,
            )
            val body = signed.entries.joinToString("&") { "${it.key}=${it.value}" }
                .toRequestBody(FORM_TYPE)

            val request = Request.Builder()
                .url("$srv/webapi/v1/door_lock/command/parse")
                .post(body)
                .build()

            val response = client.newCall(request).execute()
            val bodyStr = response.body?.string() ?: ""
            val json = JSONObject(bodyStr)

            if (response.isSuccessful && json.optBoolean("result", false)) {
                val data = json.optJSONObject("data")
                val code = data?.optString("code", "") ?: ""
                val credId = data?.optInt("credential_id", 0) ?: 0
                if (code.isNotBlank()) {
                    ParseResult(true, code, credId)
                } else {
                    ParseResult(true) // 成功但无新 key
                }
            } else {
                val msg = json.optString("err_msg", "").ifBlank {
                    json.optString("msg", "解析失败 (${response.code})")
                }
                ParseResult(false, error = msg)
            }
        } catch (e: IOException) {
            ParseResult(false, error = "网络错误: ${e.message}")
        }
    }

    // ─── 从服务器获取参数 ───

    data class DoorLockInfo(
        val deviceId: Int,
        val deviceName: String,
        val mac: String,
    )

    suspend fun fetchDoorLockList(): List<DoorLockInfo> = withContext(Dispatchers.IO) {
        try {
            val token = SettingsManager.platformToken
            if (token.isBlank()) throw Exception("未登录")

            // ★ 原 App H5 源码分析:
            //   doorLockList 通过项目客户端调用 (非平台客户端 je)
            //   项目客户端: baseURL = projectInfo.server_addr, 不带 Authorization header
            //   签名密钥: 全局 SIGN_KEY

            // 项目服务器 + 平台服务器都要试
            val servers = mutableListOf<String>()
            SettingsManager.projectServerUrl.takeIf { it.isNotBlank() }?.let { servers.add(it) }
            servers.add("https://pm.whxinna.com")  // 始终作为兜底

            val errors = mutableListOf<String>()

            for (srv in servers.distinct()) {
                // 尝试两种方式: 带 Authorization 和不带
                for (useAuth in listOf(true, false)) {
                    try {
                        val signed = signRequest(key = SIGN_KEY)
                        val query = signed.entries.joinToString("&") { "${it.key}=${it.value}" }
                        val url = "$srv/webapi/v1/door_lock/list?$query"

                        val builder = Request.Builder().url(url).get()
                        if (useAuth) builder.header("Authorization", token)
                        val request = builder.build()

                        val response = client.newCall(request).execute()
                        val bodyStr = response.body?.string() ?: ""
                        val label = if (useAuth) "带Auth" else "无Auth"
                        errors.add("$srv($label) → ${response.code} ${bodyStr.take(100)}")

                        if (response.code == 404) continue
                        if (!response.isSuccessful) continue

                        val json = try { JSONObject(bodyStr) } catch (_: Exception) { continue }
                        if (!json.optBoolean("result", false)) {
                            val msg = json.optString("msg", "").ifBlank {
                                json.optString("err_msg", "")
                            }
                            if (msg.isNotBlank()) errors.add("$srv($label) 返回: $msg")
                            continue
                        }

                        var data = json.optJSONObject("data")
                        if (data == null) {
                            val b64 = json.optString("data", "")
                            if (b64.isNotBlank()) {
                                val d = try { decodeBase64Data(b64) } catch (_: Exception) { b64 }
                                data = try { JSONObject(d) } catch (_: Exception) { null }
                            }
                        }
                        val rows = data?.optJSONArray("rows") ?: org.json.JSONArray()
                        if (rows.length() > 0) {
                            return@withContext (0 until rows.length()).map { i ->
                                val item = rows.getJSONObject(i)
                                DoorLockInfo(
                                    deviceId = item.optInt("device_id", 0),
                                    deviceName = item.optString("device_name", ""),
                                    mac = item.optString("mac", ""),
                                )
                            }
                        }
                    } catch (_: java.io.IOException) { continue }
                }
            }
            throw Exception(errors.joinToString("\n"))
        } catch (e: Exception) {
            throw Exception("门锁列表请求失败: ${e.message}")
        }
    }

    // ─── 地址 + 门锁信息 (原 App getAddress() 真正调用的端点) ───

    data class AddressInfo(
        val buildingName: String,
        val floorName: String,
        val roomName: String,
        val deviceId: Int = 0,
        val deviceName: String = "",
        val mac: String = "",
        val batteryLevel: Int = 0,
        val credentialId: Int = 0,
        val chainKey: String = "",
    )

    suspend fun fetchAddress(): AddressInfo = withContext(Dispatchers.IO) {
        try {
            val token = SettingsManager.platformToken
            if (token.isBlank()) throw Exception("未登录")

            // 调试: 打印所有 SettingsManager 值
            android.util.Log.d("ServerApi", "Settings: userId=${SettingsManager.userId}, " +
                "serverAppId=${SettingsManager.serverAppId}, serverId=${SettingsManager.serverId}, " +
                "identityCode=${SettingsManager.identityCode.take(20)}..., " +
                "projectServerUrl=${SettingsManager.projectServerUrl}, " +
                "projectSignKey=${SettingsManager.projectSignKey.take(10)}...")

            // ★ 原 App gt 客户端签名方式:
            val srv = SettingsManager.projectServerUrl.ifBlank {
                throw Exception("未绑定项目")
            }

            val signed = signGtRequest()
            val query = signed.entries.joinToString("&") { (k, v) ->
                "${k}=${URLEncoder.encode(v, "UTF-8")}"
            }
            val url = "$srv/webapi/v1/student/accommodation/details?$query"

            // gt 客户端不送 Authorization header
            val request = Request.Builder().url(url).get().build()
            val response = client.newCall(request).execute()
            val bodyStr = response.body?.string() ?: ""
            android.util.Log.d("ServerApi", "fetchAddress 响应: $bodyStr")

            if (!response.isSuccessful) throw Exception("HTTP ${response.code}: ${bodyStr.take(200)}")

            val json = try { JSONObject(bodyStr) } catch (_: Exception) {
                throw Exception("JSON 解析失败: ${bodyStr.take(200)}")
            }
            if (!json.optBoolean("result", false)) {
                val msg = json.optString("msg", "").ifBlank {
                    json.optString("err_msg", "")
                }
                throw Exception(msg.ifBlank { bodyStr.take(200) })
            }

            // data 可能是 JSONObject, JSONArray, 或 base64 字符串
            var dataObj = json.optJSONObject("data")
            if (dataObj == null) {
                val dataArr = json.optJSONArray("data")
                if (dataArr != null && dataArr.length() > 0) {
                    dataObj = dataArr.optJSONObject(0)
                }
            }
            if (dataObj == null) {
                val dataB64 = json.optString("data", "")
                if (dataB64.isNotBlank()) {
                    val decoded = decodeBase64Data(dataB64)
                    dataObj = try { JSONObject(decoded) } catch (_: Exception) { null }
                }
            }
            if (dataObj == null) {
                throw Exception("data 解析失败, 完整响应: ${bodyStr.take(500)}")
            }
            // accommodation 信息在 accommodation 子对象里
            val acc = dataObj.optJSONObject("accommodation") ?: dataObj
            val building = acc.optString("building_name", "")
            val floor = acc.optString("floor_name", "")
            val room = acc.optString("room_name", "")

            // door_lock 信息 (包含 credential!)
            val doorLock = dataObj.optJSONObject("door_lock")
            val devId = doorLock?.optInt("device_id", 0) ?: 0
            val devName = doorLock?.optString("ble_name", "") ?: ""
            val mac = doorLock?.optString("ble_mac", "") ?: ""
            val battery = doorLock?.optInt("battery_level", 0) ?: 0
            val credId = doorLock?.optInt("credential_id", 0) ?: 0
            val chainKey = doorLock?.optString("credential", "") ?: ""

            return@withContext AddressInfo(
                buildingName = building,
                floorName = floor,
                roomName = room,
                deviceId = devId,
                deviceName = devName,
                mac = mac,
                batteryLevel = battery,
                credentialId = credId,
                chainKey = chainKey,
            )
        } catch (e: Exception) {
            throw Exception("地址信息请求失败: ${e.message}")
        }
    }

    data class CredentialInfo(
        val credentialId: Int,
        val chainKey: String,
        val projectId: Int,
        val isActive: Boolean,
    )

    suspend fun fetchCredentials(deviceId: Int): List<CredentialInfo> = withContext(Dispatchers.IO) {
        try {
            val token = SettingsManager.platformToken
            if (token.isBlank()) return@withContext emptyList()

            val signed = signRequest("device_id" to deviceId.toString())
            val query = signed.entries.joinToString("&") { "${it.key}=${it.value}" }
            val request = Request.Builder()
                .url("${apiServer()}/webapi/v1/staff/door_lock/credentials?$query")
                .get()
                .header("Authorization", token)
                .build()

            val response = client.newCall(request).execute()
            val bodyStr = response.body?.string() ?: ""
            val json = JSONObject(bodyStr)
            var data = json.optJSONObject("data")
            if (data == null) {
                val b64 = json.optString("data", "")
                if (b64.isNotBlank()) {
                    val d = try { decodeBase64Data(b64) } catch (_: Exception) { b64 }
                    data = try { JSONObject(d) } catch (_: Exception) { null }
                }
            }
            val rows = data?.optJSONArray("rows") ?: org.json.JSONArray()
            if (rows.length() == 0 && bodyStr.isNotBlank()) {
                throw Exception("空列表, 服务器返回: ${bodyStr.take(300)}")
            }

            (0 until rows.length()).map { i ->
                val item = rows.getJSONObject(i)
                CredentialInfo(
                    credentialId = item.optInt("credential_id", 0),
                    chainKey = item.optString("credential", ""),
                    projectId = item.optInt("project_id", 0),
                    isActive = item.optInt("is_active", 0) == 1,
                )
            }
        } catch (e: IOException) {
            emptyList()
        }
    }
}

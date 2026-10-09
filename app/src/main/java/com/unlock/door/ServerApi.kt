package com.unlock.door

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
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
        .callTimeout(12, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    // 可取消的网络请求：用户离开页面或总恢复超时后，立即取消请求。
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private suspend fun execute(request: Request): Response = suspendCancellableCoroutine { cont ->
        val call = client.newCall(request)
        cont.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (cont.isActive) cont.resumeWithException(e)
            }
            override fun onResponse(call: Call, response: Response) {
                cont.resume(response) { response.close() }
            }
        })
    }

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

            val request = Request.Builder().url(url).get().build()
            val response = client.newCall(request).execute()
            val bodyStr = response.body?.string() ?: ""

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
        val requiresVerification: Boolean = false,
    )

    suspend fun login(phone: String, password: String): LoginResult = withContext(Dispatchers.IO) {
        try {
            // GET /webapi/users/login (原 App 实际走这个 — cA = Ve = je.get("/webapi/users/login"))
            val signed = signRequest(
                "phone" to phone,
                "pwd" to password,
            )
            val query = signed.entries.joinToString("&") { "${it.key}=${URLEncoder.encode(it.value, "UTF-8")}" }

            LoginServerFallback.login(
                preferredServer = SettingsManager.serverBaseUrl,
                attempt = { server -> loginAtServer(server, query) },
                rememberServer = { server -> SettingsManager.serverBaseUrl = server },
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            LoginResult(false, error = "${e.javaClass.simpleName}: ${e.message}")
        }
    }

    private suspend fun loginAtServer(server: String, query: String): LoginResult {
        val request = Request.Builder().url("$server/webapi/users/login?$query").get().build()
        execute(request).use { response ->
            val bodyStr = response.body?.string() ?: ""
            if (response.code == 404 || bodyStr.isBlank()) {
                return LoginResult(false, error = "HTTP ${response.code}，服务器未提供登录结果")
            }
            val json = try { JSONObject(bodyStr) } catch (_: Exception) {
                return LoginResult(false, error = "服务器响应解析失败（HTTP ${response.code}）")
            }
            if (!json.optBoolean("result", false)) {
                val message = json.optString("msg", "").ifBlank {
                    json.optString("message", "").ifBlank { json.optString("err_msg", "") }
                }
                if (message.contains("需要进行验证") || message.contains("需要验证") ||
                    message.contains("验证码") || message.contains("verification", ignoreCase = true)) {
                    return LoginResult(false, error = "服务器要求额外验证，请先在官方 App 完成验证", requiresVerification = true)
                }
                return LoginResult(false, error = "HTTP ${response.code}: ${message.ifBlank { "登录失败" }}")
            }

            // 1. 先查顶层 platform_token
            var token = json.optString("platform_token", "")
            if (token.isBlank()) token = json.optJSONObject("data")?.optString("platform_token", "") ?: ""

            // 2. 解析 data (可能是 base64 字符串, 也可能是 JSON 对象)
            var dataJson: JSONObject? = json.optJSONObject("data")
            if (dataJson == null) {
                val dataB64 = json.optString("data", "")
                if (dataB64.isNotBlank()) {
                    val decoded = decodeBase64Data(dataB64)
                    dataJson = try { JSONObject(decoded) } catch (_: Exception) { null }
                }
            }
            if (token.isBlank()) token = dataJson?.optString("platform_token", "") ?: ""
            if (token.isBlank()) {
                return LoginResult(false, error = "服务器未返回登录令牌（HTTP ${response.code}）")
            }
            if (dataJson != null) {
                // 保存用户 ID 和 identity_code (项目服务器会话令牌)
                val userInfo = dataJson.optJSONObject("user_info")
                val uid = userInfo?.optString("id", "") ?: ""
                val idCode = userInfo?.optString("identity_code", "") ?: ""
                if (uid.isNotBlank()) {
                    SettingsManager.userId = uid
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

            SettingsManager.platformToken = token
            return LoginResult(true, token, SettingsManager.projectServerUrl)
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

    suspend fun fetchAddress(targetDeviceId: Int? = null): AddressInfo = withContext(Dispatchers.IO) {
        try {
            val token = SettingsManager.platformToken
            if (token.isBlank()) throw SessionExpiredException("未登录，需要重新登录")

            // ★ 原 App 的 getAddress() 实际调用的是 keyList 端点 (bN = Ca = () => gt.get(ht.keyList)):
            //   ht.keyList = "/webapi/v1/staff/credentials"  → 返回门锁地址数组 (含 device_id / credential)
            //   注意: 不是 /student/accommodation/details (那个现在只返回住宿地址, 不含门锁)
            val srv = SettingsManager.projectServerUrl.ifBlank {
                throw Exception("未绑定项目")
            }

            val signed = signGtRequest()
            val query = signed.entries.joinToString("&") { (k, v) ->
                "${k}=${URLEncoder.encode(v, "UTF-8")}"
            }
            val url = "$srv/webapi/v1/staff/credentials?$query"

            // gt 客户端不送 Authorization header
            val request = Request.Builder().url(url).get().build()
            val response = execute(request)
            val bodyStr = response.body?.string() ?: ""
            if (response.code == 401) throw SessionExpiredException()
            val json = try { JSONObject(bodyStr) } catch (_: Exception) {
                throw Exception("服务器响应解析失败（HTTP ${response.code}）")
            }
            val error = json.optString("msg", "").ifBlank { json.optString("err_msg", "") }
            if (error.contains("login_timeout", ignoreCase = true)) throw SessionExpiredException()
            if (error.contains("需要进行验证") || error.contains("需要验证")) throw VerificationRequiredException()
            if (!response.isSuccessful) throw Exception("HTTP ${response.code}: $error")
            if (!json.optBoolean("result", false)) {
                val msg = json.optString("msg", "").ifBlank {
                    json.optString("err_msg", "")
                }
                throw Exception(msg.ifBlank { "服务器未返回错误原因" })
            }

            // ★ 原 App getAddress() 返回的 data 是「门锁地址」数组:
            //   r.data[0].building_name / device_id / ble_name / ble_mac / credential / credential_id ...
            //   字段是扁平结构, 直接平铺在数组元素上 (见 localStorage 的 doorLockAddress)
            fun selectDevice(items: JSONArray): JSONObject? {
                // 设置页首次获取仍用第一项；开门恢复时优先匹配当前门锁。
                if (targetDeviceId == null) return items.optJSONObject(0)
                for (i in 0 until items.length()) {
                    val candidate = items.optJSONObject(i) ?: continue
                    val lock = candidate.optJSONObject("door_lock") ?: candidate
                    if (lock.optInt("device_id") == targetDeviceId) return candidate
                }
                return null
            }
            var item: JSONObject? = json.optJSONArray("data")?.let { selectDevice(it) }

            // 兼容: data 是单个 JSONObject
            if (item == null) {
                item = json.optJSONObject("data")
            }
            // 兼容: data 是 base64 字符串
            if (item == null) {
                val dataB64 = json.opt("data") as? String ?: ""
                if (dataB64.isNotBlank()) {
                    val decoded = decodeBase64Data(dataB64)
                    item = try { JSONObject(decoded) } catch (_: Exception) {
                        try { selectDevice(JSONArray(decoded)) } catch (_: Exception) { null }
                    }
                }
            }
            if (item == null) {
                // data 为空数组/无门锁地址 → 返回全 0, 上层显示「未找到门锁设备」
                android.util.Log.d("ServerApi", "fetchAddress: 未找到当前门锁")
                return@withContext AddressInfo(buildingName = "", floorName = "", roomName = "")
            }

            // 兼容旧结构: 字段若嵌套在 door_lock / accommodation 子对象里则取子对象, 否则取元素本身
            val doorLock = item.optJSONObject("door_lock") ?: item
            val acc = item.optJSONObject("accommodation") ?: item

            val building = acc.optString("building_name", "")
            val floor = acc.optString("floor_name", "")
            val room = acc.optString("room_name", "")

            val devId = doorLock.optInt("device_id", 0)
            val devName = doorLock.optString("ble_name", "")
            val mac = doorLock.optString("ble_mac", "")
            val battery = doorLock.optInt("battery_level", 0)
            val credId = doorLock.optInt("credential_id", 0)
            val chainKey = doorLock.optString("credential", "")

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
        } catch (e: CancellationException) {
            throw e
        } catch (e: SessionExpiredException) {
            throw e
        } catch (e: VerificationRequiredException) {
            throw e
        } catch (e: Exception) {
            throw Exception("地址信息请求失败: ${e.message}", e)
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

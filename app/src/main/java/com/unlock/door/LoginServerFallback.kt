package com.unlock.door

import kotlinx.coroutines.CancellationException

/** 手动和自动登录共用候选列表，只优先使用已知登录服务器。 */
internal object LoginServerFallback {
    private val servers = listOf(
        "https://pm.whxinna.com",
        "https://m5-zhuli.whxinna.com",
        "https://p4-zhuli.whxinna.com",
        "https://f5-zhuli.whxinna.com",
    )

    suspend fun login(
        preferredServer: String,
        attempt: suspend (String) -> ServerApi.LoginResult,
        rememberServer: (String) -> Unit,
    ): ServerApi.LoginResult {
        val candidates = if (preferredServer in servers) {
            listOf(preferredServer) + servers.filterNot { it == preferredServer }
        } else {
            servers
        }
        val failures = mutableListOf<String>()
        for (server in candidates) {
            val result = try {
                attempt(server)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ServerApi.LoginResult(false, error = "${e.javaClass.simpleName}: ${e.message}")
            }
            if (result.success) {
                rememberServer(server)
                return result
            }
            // 明确要求验证时交由用户处理，不能靠更换服务器继续尝试。
            if (result.requiresVerification) return result
            failures.add("$server → ${result.error}")
        }
        return ServerApi.LoginResult(false, error = failures.joinToString("\n"))
    }
}

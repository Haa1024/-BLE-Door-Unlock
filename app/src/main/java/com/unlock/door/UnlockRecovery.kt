package com.unlock.door

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

class SessionExpiredException(message: String = "登录状态已过期") : Exception(message)
class VerificationRequiredException : Exception("服务器要求额外验证，请先在官方 App 完成验证，再回来重试")
class UserActionRequiredException(message: String) : Exception(message)
class CredentialRefreshRequiredException(message: String) : Exception(message)

/** 每次点击独立计数；蓝牙最多首次 + 3 次重试，登录最多 3 轮，每轮最多 4 个服务器。 */
internal class UnlockRecovery(
    private val unlockOnce: suspend () -> Unit,
    private val refreshCredentials: suspend () -> Unit,
    private val login: suspend () -> Unit,
    private val onProgress: (String) -> Unit,
    private val pause: suspend (Long) -> Unit = { delay(it) },
) {
    private var loginAttempts = 0
    private var refreshAttempts = 0

    suspend fun run() {
        var needsRefresh = false
        var previousFailure = ""
        for (attempt in 0..3) {
            val label = if (attempt == 0) "首次尝试" else "重试 $attempt/3"
            onProgress(listOf(label, previousFailure).filter { it.isNotBlank() }.joinToString("\n"))
            try {
                if (needsRefresh) {
                    refreshWithRecovery()
                    needsRefresh = false
                }
                onProgress(label)
                unlockOnce()
                return
            } catch (e: CancellationException) {
                throw e
            } catch (e: VerificationRequiredException) {
                throw e
            } catch (e: UserActionRequiredException) {
                throw e
            } catch (e: Exception) {
                if (e is CredentialRefreshRequiredException) needsRefresh = true
                previousFailure = e.message ?: "开门失败"
                if (attempt == 3) throw Exception("已重试 3 次，仍未成功：$previousFailure", e)
                onProgress("$previousFailure\n即将自动重试（${attempt + 1}/3）…")
                pause(500L * (attempt + 1))
            }
        }
    }

    private suspend fun refreshWithRecovery() {
        while (refreshAttempts < 4) {
            onProgress("正在刷新开门凭证（尝试 ${refreshAttempts + 1}/4）…")
            try {
                refreshCredentials()
                onProgress("凭证已更新，准备重新连接门锁…")
                return
            } catch (e: CancellationException) {
                throw e
            } catch (e: SessionExpiredException) {
                // 会话失效不消耗网络重试次数，但重新登录共享同一个 3 轮预算。
                relogin()
            } catch (e: VerificationRequiredException) {
                throw e
            } catch (e: UserActionRequiredException) {
                throw e
            } catch (e: Exception) {
                refreshAttempts++
                if (refreshAttempts == 4) {
                    throw UserActionRequiredException("凭证刷新已重试 3 次，仍未成功：${e.message}")
                }
                onProgress("凭证刷新失败：${e.message}\n正在重试（$refreshAttempts/3）…")
                pause(500L * refreshAttempts)
            }
        }
        throw UserActionRequiredException("凭证刷新重试次数已用完，请稍后再试")
    }

    private suspend fun relogin() {
        var lastError = "服务器仍报告登录状态已过期"
        while (loginAttempts < 3) {
            loginAttempts++
            onProgress("登录状态已过期，正在重新登录（第 $loginAttempts/3 轮）…")
            try {
                login()
                onProgress("重新登录成功，正在刷新开门凭证…")
                return
            } catch (e: CancellationException) {
                throw e
            } catch (e: VerificationRequiredException) {
                throw e
            } catch (e: UserActionRequiredException) {
                throw e
            } catch (e: Exception) {
                lastError = e.message ?: "登录失败"
                onProgress("重新登录失败（第 $loginAttempts/3 轮）：$lastError")
                if (loginAttempts < 3) pause(500L * loginAttempts)
            }
        }
        throw UserActionRequiredException("已尝试重新登录 3 轮，仍未成功：$lastError。请在设置中检查账号或使用官方 App 完成验证")
    }
}

package com.unlock.door

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class UnlockRecoveryTest {
    private val messages = mutableListOf<String>()

    private fun recovery(
        open: suspend () -> Unit,
        refresh: suspend () -> Unit = { error("不应刷新") },
        login: suspend () -> Unit = { error("不应登录") },
        pause: suspend (Long) -> Unit = {},
    ) = UnlockRecovery(open, refresh, login, { messages.add(it) }, pause)

    private suspend fun failure(block: suspend () -> Unit): Exception {
        try { block() } catch (e: Exception) { return e }
        throw AssertionError("应该失败")
    }

    @Test fun firstSuccessDoesNotLoginOrRetry() = runBlocking {
        var opens = 0
        recovery({ opens++ }).run()
        assertEquals(1, opens)
    }

    @Test fun packetTimeoutGetsExactlyThreeRetries() = runBlocking {
        var opens = 0
        val error = failure { recovery({ opens++; throw Exception("0x75 分包 2 超时") }).run() }
        assertEquals(4, opens)
        assertTrue(error.message!!.contains("已重试 3 次"))
        assertTrue(error.message!!.contains("0x75 分包 2 超时"))
        assertTrue(messages.any { it.contains("重试 3/3") })
    }

    @Test fun successDuringRetryStopsImmediately() = runBlocking {
        var opens = 0
        recovery({ if (++opens < 3) throw Exception("蓝牙断开") }).run()
        assertEquals(3, opens)
    }

    @Test fun expiredSessionLogsInThenRefreshesBeforeOpening() = runBlocking {
        val events = mutableListOf<String>()
        var authenticated = false
        recovery(
            open = {
                events.add("open")
                if (!authenticated) throw CredentialRefreshRequiredException("凭证过期")
            },
            refresh = {
                events.add("refresh")
                if (!authenticated) throw SessionExpiredException()
            },
            login = { events.add("login"); authenticated = true },
        ).run()
        assertEquals(listOf("open", "refresh", "login", "refresh", "open"), events)
        assertTrue(messages.any { it.contains("重新登录成功") })
    }

    @Test fun loginCanSucceedOnThirdAttempt() = runBlocking {
        var opens = 0
        var logins = 0
        var authenticated = false
        recovery(
            open = { if (++opens == 1) throw CredentialRefreshRequiredException("次数用完") },
            refresh = { if (!authenticated) throw SessionExpiredException() },
            login = { if (++logins < 3) throw Exception("网络超时"); authenticated = true },
        ).run()
        assertEquals(3, logins)
        assertEquals(2, opens)
    }

    @Test fun loginExhaustionDoesNotRestartLoginBudgetOrOpen() = runBlocking {
        var opens = 0
        var logins = 0
        val error = failure {
            recovery(
                open = { opens++; throw CredentialRefreshRequiredException("凭证过期") },
                refresh = { throw SessionExpiredException() },
                login = { logins++; throw Exception("登录失败") },
            ).run()
        }
        assertEquals(1, opens)
        assertEquals(3, logins)
        assertTrue(error.message!!.contains("重新登录 3 轮"))
    }

    @Test fun successfulLoginWithStillExpiredSessionIsAlsoBounded() = runBlocking {
        var logins = 0
        failure {
            recovery(
                open = { throw CredentialRefreshRequiredException("凭证过期") },
                refresh = { throw SessionExpiredException() },
                login = { logins++ },
            ).run()
        }
        assertEquals(3, logins)
    }

    @Test fun verificationStopsAfterOneLoginAndPreservesExplanation() = runBlocking {
        var logins = 0
        val error = failure {
            recovery(
                open = { throw CredentialRefreshRequiredException("凭证过期") },
                refresh = { throw SessionExpiredException() },
                login = { logins++; throw VerificationRequiredException() },
            ).run()
        }
        assertEquals(1, logins)
        assertTrue(error is VerificationRequiredException)
        assertTrue(error.message!!.contains("官方 App"))
    }

    @Test fun refreshNetworkFailureGetsOnlyThreeRetries() = runBlocking {
        var opens = 0
        var refreshes = 0
        val error = failure {
            recovery(
                open = { opens++; throw CredentialRefreshRequiredException("凭证过期") },
                refresh = { refreshes++; throw Exception("网络不可用") },
            ).run()
        }
        assertEquals(4, refreshes)
        assertEquals(1, opens)
        assertTrue(error.message!!.contains("刷新已重试 3 次"))
    }

    @Test fun repeatedExpiredCredentialsDoNotResetLoginBudget() = runBlocking {
        var opens = 0
        var logins = 0
        var freshSession = false
        failure {
            recovery(
                open = { opens++; freshSession = false; throw CredentialRefreshRequiredException("仍然过期") },
                refresh = { if (!freshSession) throw SessionExpiredException() },
                login = { logins++; freshSession = true },
            ).run()
        }
        assertEquals(4, opens)
        assertEquals(3, logins)
    }

    @Test fun cancellationDuringRetryDelayDoesNotStartAnotherAttempt() = runBlocking {
        var opens = 0
        val error = failure {
            recovery(
                open = { opens++; throw Exception("蓝牙断开") },
                pause = { throw CancellationException("取消") },
            ).run()
        }
        assertTrue(error is CancellationException)
        assertEquals(1, opens)
    }

    @Test fun userActionRequiredDoesNotRetry() = runBlocking {
        var opens = 0
        val error = failure { recovery({ opens++; throw UserActionRequiredException("请先开启蓝牙") }).run() }
        assertEquals(1, opens)
        assertEquals("请先开启蓝牙", error.message)
    }
}

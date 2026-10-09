package com.unlock.door

import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class LoginServerFallbackTest {
    private val servers = listOf(
        "https://pm.whxinna.com",
        "https://m5-zhuli.whxinna.com",
        "https://p4-zhuli.whxinna.com",
        "https://f5-zhuli.whxinna.com",
    )

    private suspend fun failure(block: suspend () -> Unit): Exception {
        try { block() } catch (e: Exception) { return e }
        throw AssertionError("应该失败")
    }

    @Test fun noSavedServerCanReachFourthCandidate() = runBlocking {
        val attempts = mutableListOf<String>()
        var remembered = ""
        val result = LoginServerFallback.login(
            preferredServer = "",
            attempt = { server ->
                attempts.add(server)
                ServerApi.LoginResult(server == servers.last(), error = "登录失败")
            },
            rememberServer = { remembered = it },
        )
        assertTrue(result.success)
        assertEquals(servers, attempts)
        assertEquals(servers.last(), remembered)
    }

    @Test fun nextLoginUsesLastSuccessfulServerFirst() = runBlocking {
        var remembered = ""
        LoginServerFallback.login(
            preferredServer = remembered,
            attempt = { ServerApi.LoginResult(it == servers[1], error = "登录失败") },
            rememberServer = { remembered = it },
        )
        val attempts = mutableListOf<String>()
        val result = LoginServerFallback.login(
            preferredServer = remembered,
            attempt = { server -> attempts.add(server); ServerApi.LoginResult(true) },
            rememberServer = { remembered = it },
        )
        assertTrue(result.success)
        assertEquals(listOf(servers[1]), attempts)
        assertEquals(servers[1], remembered)
    }

    @Test fun preferredServerNetworkFailureFallsBackAndUpdatesRecord() = runBlocking {
        val attempts = mutableListOf<String>()
        var remembered = servers[2]
        val result = LoginServerFallback.login(
            preferredServer = remembered,
            attempt = { server ->
                attempts.add(server)
                if (server == servers[2]) throw IOException("连接超时")
                ServerApi.LoginResult(server == servers[1], error = "登录失败")
            },
            rememberServer = { remembered = it },
        )
        assertTrue(result.success)
        assertEquals(listOf(servers[2], servers[0], servers[1]), attempts)
        assertEquals(servers[1], remembered)
    }

    @Test fun unknownSavedAddressIsNeverContacted() = runBlocking {
        val attempts = mutableListOf<String>()
        val records = mutableListOf<String>()
        val result = LoginServerFallback.login(
            preferredServer = "https://unknown.example",
            attempt = { server -> attempts.add(server); ServerApi.LoginResult(false, error = "登录失败") },
            rememberServer = { records.add(it) },
        )
        assertFalse(result.success)
        assertEquals(servers, attempts)
        assertTrue(records.isEmpty())
    }

    @Test fun allFailuresTryEachCandidateOnceAndKeepPreviousRecord() = runBlocking {
        val attempts = mutableListOf<String>()
        var remembered = servers[1]
        val result = LoginServerFallback.login(
            preferredServer = remembered,
            attempt = { server -> attempts.add(server); ServerApi.LoginResult(false, error = "登录失败") },
            rememberServer = { remembered = it },
        )
        assertFalse(result.success)
        assertEquals(listOf(servers[1], servers[0], servers[2], servers[3]), attempts)
        assertEquals(servers[1], remembered)
        servers.forEach { assertTrue(result.error.contains(it)) }
    }

    @Test fun verificationStopsWithoutTryingOtherServersOrChangingRecord() = runBlocking {
        val attempts = mutableListOf<String>()
        val records = mutableListOf<String>()
        val result = LoginServerFallback.login(
            preferredServer = servers[2],
            attempt = { server ->
                attempts.add(server)
                ServerApi.LoginResult(false, error = "需要验证码", requiresVerification = true)
            },
            rememberServer = { records.add(it) },
        )
        assertTrue(result.requiresVerification)
        assertEquals("需要验证码", result.error)
        assertEquals(listOf(servers[2]), attempts)
        assertTrue(records.isEmpty())
    }

    @Test fun cancellationStopsWithoutTryingOtherServersOrChangingRecord() = runBlocking {
        val attempts = mutableListOf<String>()
        val records = mutableListOf<String>()
        val error = failure {
            LoginServerFallback.login(
                preferredServer = servers[1],
                attempt = { server -> attempts.add(server); throw CancellationException("取消") },
                rememberServer = { records.add(it) },
            )
        }
        assertTrue(error is CancellationException)
        assertEquals(listOf(servers[1]), attempts)
        assertTrue(records.isEmpty())
    }

    @Test fun recoveryRemainsBoundedToThreeRoundsOfFourServers() = runBlocking {
        val attempts = mutableListOf<String>()
        var opens = 0
        val error = failure {
            UnlockRecovery(
                unlockOnce = { opens++; throw CredentialRefreshRequiredException("凭证过期") },
                refreshCredentials = { throw SessionExpiredException() },
                login = {
                    val result = LoginServerFallback.login(
                        preferredServer = "",
                        attempt = { server -> attempts.add(server); throw IOException("网络不可用") },
                        rememberServer = { throw AssertionError("失败时不应保存服务器") },
                    )
                    if (!result.success) throw Exception(result.error)
                },
                onProgress = {},
                pause = {},
            ).run()
        }
        assertTrue(error is UserActionRequiredException)
        assertTrue(error.message!!.contains("重新登录 3 轮"))
        assertEquals(1, opens)
        assertEquals(servers + servers + servers, attempts)
    }
}

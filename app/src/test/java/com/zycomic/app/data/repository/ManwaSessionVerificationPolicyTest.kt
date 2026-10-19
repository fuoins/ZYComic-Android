package com.zycomic.app.data.repository

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.CancellationException

class ManwaSessionVerificationPolicyTest {
    @Test
    fun `cached profile restoration remains unknown until remote verification`() {
        val policy = ManwaSessionVerificationPolicy()
        val cachedProfile = "cached-user"
        var profile = cachedProfile
        var status = ManwaSessionStatus.UNKNOWN

        policy.restore(cachedProfile) {
            status = ManwaSessionStatus.UNKNOWN
        }

        assertEquals(cachedProfile, profile)
        assertEquals(ManwaSessionStatus.UNKNOWN, status)
        val ticket = policy.beginRefresh { status = ManwaSessionStatus.UNKNOWN }
        assertTrue(policy.commitVerified(ticket, cachedProfile) { status = ManwaSessionStatus.VERIFIED })
        assertEquals(ManwaSessionStatus.VERIFIED, status)
        assertEquals(cachedProfile, profile)
    }

    @Test
    fun `only explicit HTTP 401 is session expiration evidence`() {
        assertTrue(ManwaSessionVerificationPolicy.isExplicitUnauthorized(401))
        assertFalse(ManwaSessionVerificationPolicy.isExplicitUnauthorized(403))
        assertFalse(ManwaSessionVerificationPolicy.isExplicitUnauthorized(500))
        assertFalse(ManwaSessionVerificationPolicy.isExplicitUnauthorized(0))
    }

    @Test
    fun `business responses and malformed or empty profiles remain unknown`() {
        assertTrue(ManwaSessionVerificationPolicy.hasSuccessfulProfileResponse(code = 1, hasData = true))
        assertFalse(ManwaSessionVerificationPolicy.hasSuccessfulProfileResponse(code = 0, hasData = true))
        assertFalse(ManwaSessionVerificationPolicy.hasSuccessfulProfileResponse(code = -1, hasData = true))
        assertFalse(ManwaSessionVerificationPolicy.hasSuccessfulProfileResponse(code = 401, hasData = true))
        assertFalse(ManwaSessionVerificationPolicy.hasSuccessfulProfileResponse(code = 1, hasData = false))
        assertFalse(ManwaSessionVerificationPolicy.isValidIdentity(""))
        assertFalse(ManwaSessionVerificationPolicy.isValidIdentity("0"))
        assertTrue(ManwaSessionVerificationPolicy.isValidIdentity("42"))
        assertFalse(ManwaSessionVerificationPolicy.isExplicitUnauthorized(403))
    }

    @Test
    fun `old refresh cannot commit after login account switch or logout`() {
        val policy = ManwaSessionVerificationPolicy()
        policy.restore("account-a") {}
        val oldRefresh = policy.beginRefresh {}

        val loginGeneration = policy.beginLogin {}
        val currentGeneration = policy.establishLogin(loginGeneration, "account-b") {}
        assertTrue(currentGeneration != null)
        assertFalse(policy.commitVerified(oldRefresh, "account-a") { error("stale result committed") })
        assertFalse(policy.markUnknown(oldRefresh) { error("stale refresh error changed state") })
        assertFalse(policy.expire(oldRefresh) { error("stale HTTP 401 expired the newer login") })

        val refreshAfterLogin = policy.beginRefresh {}
        val logoutGeneration = policy.beginLocalTransition {}
        assertFalse(policy.finishLocalTransition(logoutGeneration - 1) { error("stale logout committed") })
        assertFalse(policy.commitVerified(refreshAfterLogin, "account-b") { error("post-logout result committed") })
        assertFalse(policy.markUnknown(refreshAfterLogin) { error("post-logout error changed state") })
        assertFalse(policy.expire(refreshAfterLogin) { error("post-logout HTTP 401 changed state") })
        assertTrue(policy.finishLocalTransition(logoutGeneration) {})
    }

    @Test
    fun `account mismatch cannot verify and cancellation remains cancellation`() {
        val policy = ManwaSessionVerificationPolicy()
        policy.restore("account-a") {}
        val ticket = policy.beginRefresh {}

        assertFalse(policy.commitVerified(ticket, "account-b") { error("different account committed") })
        val cancellation = CancellationException("caller cancelled")
        val observed = try {
            throw cancellation
        } catch (error: CancellationException) {
            error
        }
        assertTrue(observed === cancellation)
        assertTrue(policy.isCurrent(ticket))
    }

    @Test
    fun `refresh started during login cannot commit and failed login releases the fence`() {
        val policy = ManwaSessionVerificationPolicy()
        policy.restore("cached-account") {}
        val loginGeneration = policy.beginLogin {}
        val overlappingRefresh = policy.beginRefresh {}

        assertFalse(policy.commitVerified(overlappingRefresh, "cached-account") { error("overlapping refresh committed") })
        policy.cancelLogin(loginGeneration) {}

        val retry = policy.beginRefresh {}
        assertTrue(policy.commitVerified(retry, "cached-account") {})
    }
}

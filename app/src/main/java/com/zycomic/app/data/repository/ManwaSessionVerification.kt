package com.zycomic.app.data.repository

/** Authentication confidence for the Manwa account, separate from the cached profile. */
enum class ManwaSessionStatus {
    SIGNED_OUT,
    UNKNOWN,
    VERIFIED,
    EXPIRED,
}

/**
 * Owns the revision and account identity that fence asynchronous session checks. Callbacks run
 * while the policy is synchronized so state publication is atomic with respect to account changes.
 */
internal class ManwaSessionVerificationPolicy {
    internal class Ticket internal constructor(
        val generation: Long,
        val accountIdentity: String?,
    )

    private var generation = 0L
    private var accountIdentity: String? = null
    private var loginPending = false

    @Synchronized
    fun beginLogin(onStart: () -> Unit): Long {
        generation++
        accountIdentity = null
        loginPending = true
        onStart()
        return generation
    }

    @Synchronized
    fun establishLogin(loginGeneration: Long, identity: String?, onStart: () -> Unit): Long? {
        if (generation != loginGeneration || !loginPending) return null
        generation++
        accountIdentity = identity
        loginPending = false
        onStart()
        return generation
    }

    @Synchronized
    fun cancelLogin(loginGeneration: Long, onFailure: () -> Unit) {
        if (generation != loginGeneration || !loginPending) return
        generation++
        accountIdentity = null
        loginPending = false
        onFailure()
    }

    @Synchronized
    fun restore(identity: String, onRestore: () -> Unit) {
        if (accountIdentity != identity || loginPending) {
            generation++
            accountIdentity = identity
            loginPending = false
        }
        onRestore()
    }

    @Synchronized
    fun beginRefresh(onStart: () -> Unit): Ticket {
        onStart()
        return Ticket(generation, accountIdentity)
    }

    @Synchronized
    fun commitVerified(ticket: Ticket, identity: String, onVerified: () -> Unit): Boolean {
        if (!matches(ticket) || !isValidIdentity(identity)) return false
        if (ticket.accountIdentity != null && ticket.accountIdentity != identity) return false
        if (accountIdentity == null) {
            accountIdentity = identity
            generation++
        }
        onVerified()
        return true
    }

    @Synchronized
    fun markUnknown(ticket: Ticket, onUnknown: () -> Unit): Boolean {
        if (!matches(ticket)) return false
        onUnknown()
        return true
    }

    @Synchronized
    fun expire(ticket: Ticket, onExpired: () -> Unit): Boolean {
        if (!matches(ticket)) return false
        generation++
        accountIdentity = null
        loginPending = false
        onExpired()
        return true
    }

    @Synchronized
    fun beginLocalTransition(onStart: () -> Unit): Long {
        generation++
        accountIdentity = null
        loginPending = false
        onStart()
        return generation
    }

    @Synchronized
    fun finishLocalTransition(transitionGeneration: Long, onFinished: () -> Unit): Boolean {
        if (generation != transitionGeneration) return false
        onFinished()
        return true
    }

    @Synchronized
    fun isCurrent(ticket: Ticket): Boolean = matches(ticket)

    private fun matches(ticket: Ticket): Boolean =
        !loginPending && generation == ticket.generation && accountIdentity == ticket.accountIdentity

    companion object {
        /** Empty and sentinel IDs cannot establish a verified profile; they are not proof of expiry. */
        fun isValidIdentity(identity: String): Boolean = identity.isNotEmpty() && identity != "0"

        /** The Manwa API's existing success contract is code 1; every other payload is unknown. */
        fun hasSuccessfulProfileResponse(code: Int, hasData: Boolean): Boolean = code == 1 && hasData
    }
}

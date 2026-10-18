package com.zycomic.app.ui.login

/** JM account UI capabilities backed by the supplied verified API evidence. */
internal object JmAccountCapabilityPolicy {
    fun supportsUsernamePasswordLogin(): Boolean = true
    fun supportsEmailLogin(): Boolean = false
    fun supportsRegistration(): Boolean = true
    fun supportsActivation(): Boolean = true
    fun supportsForgotPassword(): Boolean = true
    fun supportsPasswordChange(): Boolean = true
}

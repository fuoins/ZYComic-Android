package com.zycomic.app.ui.login

/** JM account UI capabilities backed by the supplied verified API evidence. */
internal object JmAccountCapabilityPolicy {
    fun supportsUsernamePasswordLogin(): Boolean = true
    fun supportsEmailLogin(): Boolean = false
    fun supportsRegistration(): Boolean = false
    fun supportsActivation(): Boolean = false
    fun supportsForgotPassword(): Boolean = false
    fun supportsPasswordChange(): Boolean = false
}

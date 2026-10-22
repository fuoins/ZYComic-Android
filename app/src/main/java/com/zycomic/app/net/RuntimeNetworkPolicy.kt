package com.zycomic.app.net

internal object RuntimeNetworkPolicy {
    fun mayRecover(internet: Boolean, validated: Boolean): Boolean = internet && validated
}

package com.zycomic.app.net

/** UI copy follows the actual candidate strategy, not whether a foreground waiter is still present. */
enum class StartupTestMode {
    QUICK,
    FULL,
}

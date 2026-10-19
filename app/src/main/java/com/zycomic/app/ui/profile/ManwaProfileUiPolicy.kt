package com.zycomic.app.ui.profile

import com.zycomic.app.data.repository.ManwaSessionStatus

internal object ManwaProfileUiPolicy {
    fun showUnverifiedWarning(status: ManwaSessionStatus, refreshing: Boolean): Boolean =
        status == ManwaSessionStatus.UNKNOWN && !refreshing
}

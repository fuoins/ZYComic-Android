package com.zycomic.app.net

import kotlinx.coroutines.sync.Semaphore

/** Scheduling only: clients, endpoint identities and per-platform state remain isolated. */
internal object SpeedTestProbeBudget {
    const val MAX_CONCURRENT_ENDPOINTS = 24
    val permits = Semaphore(MAX_CONCURRENT_ENDPOINTS)
}

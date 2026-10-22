package com.zycomic.app.net

import android.content.Context
import android.content.MemoryContext

object DevConfig {
    @Volatile var appContext: Context? = MemoryContext()
    fun resetRuntimeTestContext() {
        appContext = MemoryContext()
    }
}

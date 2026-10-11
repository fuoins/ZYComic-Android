package com.zycomic.app.platform

import android.content.Context

internal object PlatformPrefs {
    private const val NAME = "zycomic_platform_rank"
    private const val KEY_PLATFORM = "platform"
    private const val KEY_SUB = "sub"

    fun loadPlatform(ctx: Context): PlatformMeta {
        val sp = ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE)
        val raw = sp.getString(KEY_PLATFORM, null) ?: return PlatformCatalog.defaultPlatform
        return PlatformCatalog.platforms.firstOrNull { it.id.name == raw }
            ?: PlatformCatalog.defaultPlatform
    }

    fun loadSub(ctx: Context, platform: PlatformMeta): String {
        val sp = ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE)
        val sub = sp.getString(KEY_SUB, null) ?: return platform.defaultSub
        return if (platform.isValidSub(sub)) sub else platform.defaultSub
    }

    fun save(ctx: Context, platform: PlatformMeta, subId: String) {
        ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE).edit()
            .putString(KEY_PLATFORM, platform.id.name)
            .putString(KEY_SUB, subId)
            .apply()
    }
}

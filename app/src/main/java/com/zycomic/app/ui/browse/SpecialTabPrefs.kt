package com.zycomic.app.ui.browse

import android.content.Context

object SpecialTabPrefs {
    private const val NAME = "zycomic_special_tab_defaults"
    private const val KEY_RANK_TYPE = "rank_type"

    fun getRankType(ctx: Context): Int =
        ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE).getInt(KEY_RANK_TYPE, 0)

    fun setRankType(ctx: Context, v: Int) {
        ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE).edit().putInt(KEY_RANK_TYPE, v).apply()
    }
}

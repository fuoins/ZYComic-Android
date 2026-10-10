package com.zycomic.app.ui.onboarding

import android.content.Context

/**
 * 首启引导"只自动显示一次"标志。
 *
 * - 存在独立 SharedPreferences `zycomic_onboarding`，键 [KEY_DONE] 默认 false；
 * - 用户走完向导点「开始使用」后置 true，之后冷启动直接进主界面；
 * - 设置页「重新查看新手引导」会临时置 false 重新播放，走完再置回 true。
 */
object OnboardingPrefs {
    private const val PREFS_NAME = "zycomic_onboarding"
    private const val KEY_DONE = "onboarding_completed"

    fun isDone(context: Context): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_DONE, false)

    fun setDone(context: Context, done: Boolean = true) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_DONE, done)
            .apply()
    }
}

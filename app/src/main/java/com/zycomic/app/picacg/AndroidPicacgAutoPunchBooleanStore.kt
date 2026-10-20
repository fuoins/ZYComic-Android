package com.zycomic.app.picacg

import android.content.Context

/** Android persistence adapter; the default remains ON until the user explicitly changes it. */
internal class AndroidPicacgAutoPunchBooleanStore(context: Context) : PicacgAutoPunchBooleanStore {
    private val preferences = context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    override fun getBoolean(key: String, defaultValue: Boolean): Boolean = preferences.getBoolean(key, defaultValue)

    override fun putBoolean(key: String, value: Boolean) {
        preferences.edit().putBoolean(key, value).apply()
    }

    private companion object {
        const val PREFERENCES_NAME = "picacg_profile_preferences"
    }
}

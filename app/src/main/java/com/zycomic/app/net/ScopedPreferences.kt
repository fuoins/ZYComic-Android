package com.zycomic.app.net

import android.content.SharedPreferences

/**
 * A key-namespaced view over one SharedPreferences file.
 *
 * Keys listed in [globalKeys] are shared app preferences (user toggles, remote-config markers).
 * Every other key is stored as `<key>.<bucket>` so direct day/night and VPN measurements can
 * coexist in the same file without ever reading each other's values.
 */
class ScopedPreferences(
    private val base: SharedPreferences,
    @Volatile var storageKey: String,
    private val globalKeys: Set<String> = emptySet(),
) {
    fun keyFor(key: String): String = if (key in globalKeys) key else "$key.$storageKey"

    fun isScopedKey(key: String): Boolean = key !in globalKeys && key.endsWith(".$storageKey")

    fun getString(key: String, defValue: String? = null): String? = base.getString(keyFor(key), defValue)

    fun getStringSet(key: String, defValues: Set<String>? = null): Set<String>? =
        base.getStringSet(keyFor(key), defValues)

    fun getInt(key: String, defValue: Int = 0): Int = base.getInt(keyFor(key), defValue)

    fun getLong(key: String, defValue: Long = 0L): Long = base.getLong(keyFor(key), defValue)

    fun getFloat(key: String, defValue: Float = 0f): Float = base.getFloat(keyFor(key), defValue)

    fun getBoolean(key: String, defValue: Boolean = false): Boolean = base.getBoolean(keyFor(key), defValue)

    fun contains(key: String): Boolean = base.contains(keyFor(key))

    fun edit(): Editor = Editor()

    inner class Editor {
        private val editor = base.edit()

        fun putString(key: String, value: String?) = apply { editor.putString(keyFor(key), value) }

        fun putStringSet(key: String, values: Set<String>?) = apply { editor.putStringSet(keyFor(key), values) }

        fun putInt(key: String, value: Int) = apply { editor.putInt(keyFor(key), value) }

        fun putLong(key: String, value: Long) = apply { editor.putLong(keyFor(key), value) }

        fun putFloat(key: String, value: Float) = apply { editor.putFloat(keyFor(key), value) }

        fun putBoolean(key: String, value: Boolean) = apply { editor.putBoolean(keyFor(key), value) }

        fun remove(key: String) = apply { editor.remove(keyFor(key)) }

        fun clear() = apply {
            base.all.keys.filter { key -> isScopedKey(key) }.forEach { key -> editor.remove(key) }
        }

        fun commit(): Boolean = editor.commit()

        fun apply() = editor.apply()
    }
}

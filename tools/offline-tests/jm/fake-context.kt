package com.zycomic.routecache

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import java.util.concurrent.ConcurrentHashMap

class MemoryContext : ContextWrapper(null) {
    private val stores = ConcurrentHashMap<String, MemoryPreferences>()
    override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
        stores.computeIfAbsent(name) { MemoryPreferences() }
}

class MemoryPreferences : SharedPreferences {
    private val values = LinkedHashMap<String, Any>()
    override fun getAll(): MutableMap<String, *> = synchronized(values) { LinkedHashMap(values) }
    override fun getString(key: String, defValue: String?): String? = synchronized(values) { values[key] as? String ?: defValue }
    @Suppress("UNCHECKED_CAST")
    override fun getStringSet(key: String, defValues: MutableSet<String>?): MutableSet<String>? = synchronized(values) {
        (values[key] as? Set<String>)?.toMutableSet() ?: defValues?.toMutableSet()
    }
    override fun getInt(key: String, defValue: Int): Int = synchronized(values) { values[key] as? Int ?: defValue }
    override fun getLong(key: String, defValue: Long): Long = synchronized(values) { values[key] as? Long ?: defValue }
    override fun getFloat(key: String, defValue: Float): Float = synchronized(values) { values[key] as? Float ?: defValue }
    override fun getBoolean(key: String, defValue: Boolean): Boolean = synchronized(values) { values[key] as? Boolean ?: defValue }
    override fun contains(key: String): Boolean = synchronized(values) { values.containsKey(key) }
    override fun edit(): SharedPreferences.Editor = MemoryEditor()
    override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
    override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}

    private inner class MemoryEditor : SharedPreferences.Editor {
        private val updates = LinkedHashMap<String, Any?>()
        private var clear = false
        override fun putString(key: String, value: String?): SharedPreferences.Editor = update(key, value)
        override fun putStringSet(key: String, values: MutableSet<String>?): SharedPreferences.Editor = update(key, values?.toSet())
        override fun putInt(key: String, value: Int): SharedPreferences.Editor = update(key, value)
        override fun putLong(key: String, value: Long): SharedPreferences.Editor = update(key, value)
        override fun putFloat(key: String, value: Float): SharedPreferences.Editor = update(key, value)
        override fun putBoolean(key: String, value: Boolean): SharedPreferences.Editor = update(key, value)
        override fun remove(key: String): SharedPreferences.Editor = update(key, null)
        override fun clear(): SharedPreferences.Editor { clear = true; return this }
        private fun update(key: String, value: Any?): SharedPreferences.Editor { updates[key] = value; return this }
        override fun commit(): Boolean { apply(); return true }
        override fun apply() = synchronized(values) {
            if (clear) values.clear()
            updates.forEach { (key, value) -> if (value == null) values.remove(key) else values[key] = value }
        }
    }
}

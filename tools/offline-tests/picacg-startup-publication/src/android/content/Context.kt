package android.content

open class Context {
    open val applicationContext: Context get() = this
    open fun getSharedPreferences(name: String, mode: Int): SharedPreferences = error("No preferences configured")
    companion object {
        const val MODE_PRIVATE = 0
    }
}

interface SharedPreferences {
    val all: Map<String, *>
    fun contains(key: String): Boolean
    fun getString(key: String, defValue: String?): String?
    fun getBoolean(key: String, defValue: Boolean): Boolean
    fun edit(): Editor
    interface Editor {
        fun putString(key: String, value: String?): Editor
        fun putBoolean(key: String, value: Boolean): Editor
        fun remove(key: String): Editor
        fun apply()
        fun commit(): Boolean
    }
}

class MemoryContext : Context() {
    private val preferences = mutableMapOf<String, MemoryPreferences>()
    override fun getSharedPreferences(name: String, mode: Int): SharedPreferences = preferences.getOrPut(name, ::MemoryPreferences)
}

private class MemoryPreferences : SharedPreferences {
    private val data = mutableMapOf<String, Any?>()
    override val all: Map<String, *> get() = data.toMap()
    override fun contains(key: String): Boolean = key in data
    override fun getString(key: String, defValue: String?): String? = data[key] as? String ?: defValue
    override fun getBoolean(key: String, defValue: Boolean): Boolean = data[key] as? Boolean ?: defValue
    override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
        private val changes = mutableMapOf<String, Any?>()
        override fun putString(key: String, value: String?): SharedPreferences.Editor = apply { changes[key] = value }
        override fun putBoolean(key: String, value: Boolean): SharedPreferences.Editor = apply { changes[key] = value }
        override fun remove(key: String): SharedPreferences.Editor = apply { changes[key] = null }
        override fun apply() {
            changes.forEach { (key, value) -> if (value == null) data.remove(key) else data[key] = value }
        }
        override fun commit(): Boolean {
            apply()
            return true
        }
    }
}

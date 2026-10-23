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
    fun edit(): Editor
    interface Editor {
        fun putString(key: String, value: String?): Editor
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
    private val data = mutableMapOf<String, String?>()
    override val all: Map<String, *> get() = data.toMap()
    override fun contains(key: String): Boolean = key in data
    override fun getString(key: String, defValue: String?): String? = data[key] ?: defValue
    override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
        private val changes = mutableMapOf<String, String?>()
        override fun putString(key: String, value: String?): SharedPreferences.Editor = apply { changes[key] = value }
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

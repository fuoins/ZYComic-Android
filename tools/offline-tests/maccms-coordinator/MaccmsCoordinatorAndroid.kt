package android.content

open class Context {
    private val preferences = mutableMapOf<String, SharedPreferences>()
    open fun getSharedPreferences(name: String, mode: Int) = preferences.getOrPut(name) { SharedPreferences() }
    open fun getSystemService(name: String): Any? = null
    companion object {
        const val MODE_PRIVATE = 0
        const val CONNECTIVITY_SERVICE = "connectivity"
    }
}

class SharedPreferences {
    private val values = mutableMapOf<String, String>()
    fun getString(key: String, defaultValue: String?): String? = values[key] ?: defaultValue
    fun edit() = Editor(values)
    class Editor internal constructor(private val values: MutableMap<String, String>) {
        fun putString(key: String, value: String?) = apply { if (value != null) values[key] = value }
        fun apply() = Unit
    }
}

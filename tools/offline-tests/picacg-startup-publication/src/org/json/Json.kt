package org.json

private object Registry {
    private var next = 0
    private val values = mutableMapOf<String, Any>()

    @Synchronized fun save(value: Any): String = "offline-json-${++next}".also { values[it] = value }

    @Synchronized fun load(key: String): Any? = values[key]
}

class JSONObject {
    private val values = linkedMapOf<String, Any?>()
    constructor()
    constructor(serialized: String) {
        (Registry.load(serialized) as? JSONObject)?.let { values.putAll(it.values) }
    }
    fun put(key: String, value: Any?): JSONObject = apply { values[key] = value }
    fun isNull(key: String): Boolean = values[key] == null || values[key] === NULL
    fun has(key: String): Boolean = values.containsKey(key)
    fun getInt(key: String): Int = (values[key] as Number).toInt()
    fun optInt(key: String, fallback: Int = 0): Int = (values[key] as? Number)?.toInt() ?: fallback
    fun optLong(key: String, fallback: Long = 0L): Long = (values[key] as? Number)?.toLong() ?: fallback
    fun optBoolean(key: String, fallback: Boolean = false): Boolean = values[key] as? Boolean ?: fallback
    fun optString(key: String, fallback: String = ""): String = values[key]?.takeUnless { it === NULL }?.toString() ?: fallback
    fun optJSONArray(key: String): JSONArray? = values[key] as? JSONArray
    override fun toString(): String = Registry.save(JSONObject().also { it.values.putAll(values) })
    companion object {
        val NULL = Any()
    }
}

class JSONArray {
    private val values = mutableListOf<Any?>()
    constructor()
    constructor(serialized: String) {
        (Registry.load(serialized) as? JSONArray)?.let { values.addAll(it.values) }
    }
    fun put(value: Any?): JSONArray = apply { values.add(value) }
    fun length(): Int = values.size
    fun optJSONObject(index: Int): JSONObject? = values.getOrNull(index) as? JSONObject
    fun getString(index: Int): String = values[index].toString()
}

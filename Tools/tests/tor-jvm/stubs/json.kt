package org.json
class JSONObject(text: String = "{}") {
    fun put(key: String, value: Any?) = this
    fun optString(key: String) = ""
    fun optInt(key: String, fallback: Int = 0) = fallback
    fun optBoolean(key: String) = false
    fun optJSONArray(key: String): JSONArray? = null
    override fun toString() = "{}"
}
class JSONArray { fun length() = 0; fun getString(index: Int) = "" }

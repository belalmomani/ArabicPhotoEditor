package org.json
class JSONException : Exception()
class JSONArray() {
    constructor(c: Collection<*>) : this()
    fun put(v: Any?): JSONArray = TODO()
    fun length(): Int = TODO()
    fun getJSONObject(i: Int): JSONObject = TODO()
    fun optString(i: Int): String = TODO()
    fun optJSONObject(i: Int): JSONObject? = TODO()
}
class JSONObject() {
    constructor(s: String) : this()
    fun put(k: String, v: Any?): JSONObject = TODO()
    fun optString(k: String): String = TODO()
    fun optString(k: String, d: String): String = TODO()
    fun optInt(k: String): Int = TODO()
    fun optInt(k: String, d: Int): Int = TODO()
    fun optLong(k: String, d: Long): Long = TODO()
    fun optDouble(k: String, d: Double): Double = TODO()
    fun optBoolean(k: String, d: Boolean): Boolean = TODO()
    fun optJSONObject(k: String): JSONObject? = TODO()
    fun optJSONArray(k: String): JSONArray? = TODO()
    fun getJSONObject(k: String): JSONObject = TODO()
    fun getInt(k: String): Int = TODO()
    fun getString(k: String): String = TODO()
    fun toString(indent: Int): String = TODO()
}

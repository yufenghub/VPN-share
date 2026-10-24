package dev.shizzi.adb

import dev.shizzi.App
import dev.shizzi.BuildConfig
import org.json.JSONObject

object ShellRpc {
    fun token(): String {
        val preferences = App.instance.getSharedPreferences("adb_access", 0)
        synchronized(this) {
            return preferences.getString("rpc_token", null) ?: java.security.SecureRandom().let { random ->
                ByteArray(32).also(random::nextBytes).joinToString("") { "%02x".format(it.toInt() and 255) }
            }.also { check(preferences.edit().putString("rpc_token", it).commit()) }
        }
    }

    fun call(operation: String, arguments: JSONObject = JSONObject(), timeout: Int = 90000): JSONObject {
        require(operation in ShellProtocol.operations)
        val token = token()
        ShellTransport.connect(token, timeout).use { channel ->
            val request = JSONObject().put("op", operation).put("args", arguments)
            channel.write(request.toString())
            val response = JSONObject(channel.read())
            check(response.optBoolean("ok")) { response.optString("error", "本地服务错误") }
            return response
        }
    }
}

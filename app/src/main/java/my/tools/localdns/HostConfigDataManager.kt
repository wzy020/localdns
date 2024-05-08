package my.tools.localdns

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import my.tools.localdns.net.DnsChange
import org.json.JSONObject
import kotlin.Exception

object HostConfigDataManager {

    private const val HOST_CONFIG_SP = "host_config_sp"
    private const val HOST_CONFIG_KEY = "host_config_key"

    val mHostMap = LinkedHashMap<String, String>() // key:domain value:ip


    fun loadLocalConfig(context: Context, callback: ((res: Boolean) -> Unit)?) {
        val handler = Handler()
        Thread {
            try {
                Looper.prepare()
                val sp: SharedPreferences = context.getSharedPreferences(HOST_CONFIG_SP, Context.MODE_PRIVATE)
                val configData = sp.getString(HOST_CONFIG_KEY, "") ?: ""
                val hostConfig = JSONObject(configData)

                applyConfig(hostConfig)
                handler.post { callback?.invoke(true) }
            } catch (e: Exception) {
                e.printStackTrace()
                handler.post { callback?.invoke(false) }
            } finally {
                Looper.loop()
            }
        }.start()
    }


    @Throws(Exception::class)
    private fun applyConfig(hostConfig: JSONObject) {
        synchronized(HostConfigDataManager::class.java) {
            mHostMap.clear()
            hostConfig.keys().forEach { domain ->
                val ip = hostConfig.optString(domain, "")
                if (!ip.isNullOrEmpty()) {
                    // add trailing dot
                    mHostMap["$domain."] = ip
                }
            }
            DnsChange.setIPV4Map(mHostMap)
        }
    }

    @Throws(Exception::class)
    fun saveConfig(context: Context, config: String, callback: ((res: Boolean) -> Unit)?) {
        val handler = Handler()
        Thread {
            try {
                Looper.prepare()
                val jsonObject = parseConfig(config.trimIndent())
                applyConfig(jsonObject)
                val sp: SharedPreferences = context.getSharedPreferences(HOST_CONFIG_SP, Context.MODE_PRIVATE)
                val editor = sp.edit()
                editor.putString(HOST_CONFIG_KEY, jsonObject.toString())
                editor.apply()
                handler.post { callback?.invoke(true) }
            } catch (e: Exception) {
                Toast.makeText(context, e.message, Toast.LENGTH_SHORT).show()
                handler.post { callback?.invoke(false) }
            } finally {
                Looper.loop()
            }
        }.start()
    }

    @Throws(Exception::class)
    private fun parseConfig(configStr: String): JSONObject {
        val jsonObject = JSONObject()
        configStr.lines().forEach { line ->
            val parts = line.trim().split("\\s+".toRegex())
            if (parts.size == 2) {
                val ip = parts[0]
                val host = parts[1]
                jsonObject.put(host, ip)
            } else {
                throw Exception("bad config")
            }
        }
        return jsonObject
    }


}
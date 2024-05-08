package my.tools.localdns

import android.app.Activity
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import android.net.wifi.WifiManager
import android.text.format.Formatter
import android.os.PowerManager
import android.content.Intent
import android.net.Uri
import android.provider.Settings


object Utils {

    /**
     * 申请加入电池白名单
     */
    fun setPowerManager(context: Activity) {
        try {
            val pkg = context.packageName
            val powerManager = context.applicationContext.getSystemService(Context.POWER_SERVICE) as? PowerManager?
            val isIgnoring = powerManager?.isIgnoringBatteryOptimizations(pkg) ?: false
            if (isIgnoring) return
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
            intent.data = Uri.parse("package:$pkg")
            context.startActivity(intent)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * 无网络代理
     */
    fun checkNoProxy(): Boolean {
        return try {
            val host = System.getProperty("http.proxyHost")
            val port = System.getProperty("http.proxyPort")
            (host.isNullOrEmpty() && port.isNullOrEmpty())
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    /**
     * 无其他VPN
     */
    fun checkNoVPN(context: Context): Boolean {
        return try {
            val cm = context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager?
            val activeNetWork = cm?.activeNetwork
            val caps = cm?.getNetworkCapabilities(activeNetWork)
            val isVPN = caps?.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
            (true != isVPN)
        } catch (e: Exception) {
            e.printStackTrace()
            true
        }
    }

    fun getMyIP(context: Context): String {
        val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        return Formatter.formatIpAddress(wm.connectionInfo.ipAddress)
    }

    fun getSystemDNS(context: Context): String {
        val cm = context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val linkProperties = cm.getLinkProperties(cm.activeNetwork)
        val dnsServersList = linkProperties?.dnsServers ?: emptyList()
        return dnsServersList[0].hostAddress ?: ""
    }
}


object HttpUtils {

    @JvmOverloads
    @Throws(IOException::class)
    operator fun get(url: String, headers: Map<String, String>? = null): String {
        return fetch("GET", url, null, headers)
    }

    @Throws(IOException::class)
    fun fetch(method: String, url: String, body: String?, headers: Map<String, String>?): String {
        // connection
        val u = URL(url)
        val conn = u.openConnection() as HttpURLConnection
        conn.connectTimeout = 10000
        conn.readTimeout = 10000

        // method
        conn.requestMethod = method

        // headers
        if (headers != null) {
            for (key in headers.keys) {
                conn.addRequestProperty(key, headers[key])
            }
        }

        // body
        if (body != null) {
            conn.doOutput = true
            val os = conn.outputStream
            os.write(body.toByteArray())
            os.flush()
            os.close()
        }

        // response
        val inputStream = conn.inputStream
        val response = streamToString(inputStream)
        inputStream.close()

        // handle redirects
        if (conn.responseCode == 301) {
            val location = conn.getHeaderField("Location")
            return fetch(method, location, body, headers)
        }
        return response
    }

    /**
     * Read an input stream into a string
     * @param inputStream
     * @return
     * @throws IOException
     */
    @Throws(IOException::class)
    fun streamToString(inputStream: InputStream): String {
        val out = StringBuffer()
        val b = ByteArray(4096)
        var n: Int
        while (inputStream.read(b).also { n = it } != -1) {
            out.append(String(b, 0, n))
        }
        return out.toString()
    }
}
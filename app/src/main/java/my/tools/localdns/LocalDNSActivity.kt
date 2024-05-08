package my.tools.localdns

import android.app.AlertDialog
import android.app.ProgressDialog
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.AppCompatEditText
import androidx.appcompat.widget.SwitchCompat


import java.lang.StringBuilder
import kotlin.system.exitProcess


class LocalDNSActivity: AppCompatActivity() {

    private val VPN_REQUEST_CODE = 666

    private val mainHandler: Handler by lazy { Handler(Looper.getMainLooper()) }
    private var waiting = false

    private val statusText: View by lazy { findViewById(R.id.status_tv) }
    private val statusBtn: SwitchCompat by lazy { findViewById(R.id.status_btn) }

    private val editText: View by lazy { findViewById(R.id.edit_tv) }
    private val editBtn: SwitchCompat by lazy { findViewById(R.id.edit_btn) }

    private val hostTv: AppCompatEditText by lazy { findViewById(R.id.host_tv) }


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(null)
        setContentView(R.layout.activity_local_dns)
        initView()

        showProgress("Checking network...")
        checkEnv { err ->
            hideProgress()

            if (err.isNullOrEmpty()) {
                loadLocalConfig()
                return@checkEnv
            }

            AlertDialog.Builder(this).apply {
                setCancelable(false)
                setMessage(err)
                setPositiveButton("EXIT APP") { _, _ ->
                    finishAffinity()
                    android.os.Process.killProcess(android.os.Process.myPid())
                    exitProcess(0)
                }
                show()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (waiting) return
        // 如果被其他VPN挤掉线
        if (!LocalDNSService.isRunning) {
            statusBtn.isChecked = false
        }
    }

    override fun onDestroy() {
        LocalDNSService.clearResources()
        statusBtn.isChecked = false
        super.onDestroy()
    }

    private fun loadLocalConfig() {
        showProgress("Loading local config...")
        HostConfigDataManager.loadLocalConfig(this) { res ->
            updateStatusLayout(HostConfigDataManager.mHostMap.isNotEmpty())
            showHostConfig()
            hideProgress()
        }
    }

    // region VPN


    private fun startVPN() {
        waiting = true
        LocalDNSService.callPrepare(this)?.apply {
            startActivityForResult(this, VPN_REQUEST_CODE)
        } ?: run {
            onActivityResult(VPN_REQUEST_CODE, RESULT_OK, null)
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != VPN_REQUEST_CODE) return
        if (resultCode == RESULT_OK) {
            Utils.setPowerManager(this) // 开启服务之前，尝试申请电池白名单
            LocalDNSService.startLocalDNSService(this)
        }
        mainHandler.postDelayed({ waiting = false }, 500)
    }

    private fun shutdownVPN() {
        LocalDNSService.stopLocalDNSService(this)
    }

    // endregion

    // region View

    private fun initView() {

        statusBtn.isChecked = false
        updateStatusLayout(false)

        editBtn.isChecked = false
        editText.isEnabled = false

        hostTv.isEnabled = false

        statusBtn.setOnCheckedChangeListener { _, isChecked ->
            updateStatusText()
            if (isChecked) {
                startVPN()
            } else {
                shutdownVPN()
            }
        }

        editBtn.setOnCheckedChangeListener { _, isChecked ->
            editText.isEnabled = isChecked
            hostTv.isEnabled = isChecked

            if (isChecked) {
                statusBtn.isChecked = false
                updateStatusLayout(false)
                return@setOnCheckedChangeListener
            }

            val configStr = hostTv.text.toString()
            if (configStr.isNotEmpty()) {
                HostConfigDataManager.saveConfig(this, configStr) {res ->
                    if (res) {
                        updateStatusLayout(true)
                    } else {
                        editBtn.isChecked = true
                    }
                }
            }
        }
    }

    private fun updateStatusLayout(enabled: Boolean) {
        statusText.isEnabled = enabled
        statusBtn.isEnabled = enabled
        updateStatusText()
    }

    private fun updateStatusText() {
        statusText.isEnabled = statusBtn.isChecked
    }

    private fun showHostConfig() {
        Thread {
            StringBuilder().also { sb ->
                HostConfigDataManager.mHostMap.forEach { entry ->
                    val ip = entry.value
                    val domain = entry.key.removeSuffix(".")// hide trailing dot
                    sb.append("$ip $domain")
                    sb.append(System.lineSeparator())
                }
                runOnUiThread {
                    hostTv.setText(sb.toString())
                }
            }
        }.start()
    }

    // endregion

    private var progressDialog: ProgressDialog? = null

    private fun showProgress(msg: String) {
        hideProgress()
        progressDialog = ProgressDialog.show(this, "", msg)
    }

    private fun hideProgress() {
        if (progressDialog?.isShowing == true) {
            progressDialog?.dismiss()
        }
    }

    private fun checkEnv(callback: (err: String?)->Unit) {
        if (!Utils.checkNoProxy()) {
            callback.invoke("Please close Proxy.")
            return
        }

        if (!Utils.checkNoVPN(this)) {
            callback.invoke("Please close VPN.")
            return
        }

        callback.invoke(null)
    }

}
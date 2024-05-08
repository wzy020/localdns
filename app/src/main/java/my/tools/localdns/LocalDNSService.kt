package my.tools.localdns

import android.app.Notification
import my.tools.localdns.Utils.getMyIP
import my.tools.localdns.Utils.getSystemDNS
import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.os.Build
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.util.Log
import my.tools.localdns.net.ByteBufferPool
import my.tools.localdns.net.Packet
import my.tools.localdns.net.TCPInput
import my.tools.localdns.net.TCPOutput
import my.tools.localdns.net.UDPInput
import my.tools.localdns.net.UDPOutput
import my.tools.localdns.net.VPNRunnable
import java.lang.Exception
import java.lang.RuntimeException
import java.nio.ByteBuffer
import java.nio.channels.Selector
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.locks.ReentrantLock

class LocalDNSService : VpnService() {

    companion object {

        private const val TAG = "LocalDNSService"
        private const val ACTION_CONNECT = "$TAG.START"
        private const val ACTION_DISCONNECT = "$TAG.STOP"

        private var executorService: ExecutorService? = null

        private var udpSelector: Selector? = null
        private var tcpSelector: Selector? = null

        private var vpnInterface: ParcelFileDescriptor? = null

        private var udpSelectorLock: ReentrantLock? = null
        private var tcpSelectorLock: ReentrantLock? = null
        private var deviceToNetworkUDPQueue: ConcurrentLinkedQueue<Packet>? = null
        private var deviceToNetworkTCPQueue: ConcurrentLinkedQueue<Packet>? = null
        private var networkToDeviceQueue: ConcurrentLinkedQueue<ByteBuffer>? = null

        var isRunning = false


        fun startLocalDNSService(context: Context) {
            try {
                val intentConnect = Intent(context, LocalDNSService::class.java).setAction(
                    ACTION_CONNECT
                )
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intentConnect)
                } else {
                    context.startService(intentConnect)
                }
            } catch (e: RuntimeException) {
                e.printStackTrace()
            }
        }

        fun stopLocalDNSService(context: Context) {
            try {
                context.startService(Intent(context, LocalDNSService::class.java).setAction(
                    ACTION_DISCONNECT
                ))
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        fun callPrepare(context: Context): Intent? {
            return prepare(context)
        }

        fun clearResources() {
            try {
                executorService?.shutdownNow()
                udpSelectorLock = null
                tcpSelectorLock = null
                deviceToNetworkUDPQueue = null
                deviceToNetworkTCPQueue = null
                networkToDeviceQueue = null
                ByteBufferPool.clear()
                udpSelector?.close()
                tcpSelector?.close()
                vpnInterface?.close()
                udpSelector = null
                tcpSelector = null
                vpnInterface = null
                isRunning = false
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

    }

    override fun onStartCommand(intent: Intent, flags: Int, startId: Int): Int {
        when(intent.action) {
            ACTION_CONNECT -> startVPNService()
            ACTION_DISCONNECT -> stopVPNService()
            else -> Log.w(TAG, "unknown intent action: ${intent.action}")
        }
        return START_NOT_STICKY
    }

    override fun onCreate() {
        Log.i(TAG, "create")
        super.onCreate()
    }

    override fun onRevoke() {
        // 如果被其他VPN挤掉线
        stopVPNService()
        super.onRevoke()
    }

    override fun onDestroy() {
        stopVPNService()
        super.onDestroy()
    }

    private fun startVPNService() {
        clearResources()
        setNotification(true)

        val myIp = getMyIP(this)
        val systemDNS = getSystemDNS(this)
        val builder = Builder()
        builder.addAddress(myIp, 32)
        builder.addRoute(systemDNS, 32)
        builder.addDnsServer(systemDNS)
        vpnInterface = builder.setSession(getString(R.string.app_name)).establish()

        if (vpnInterface == null) {
            Log.d(TAG, "unknown error")
            stopVPNService()
            return
        }

        try {
            udpSelector = Selector.open()
            tcpSelector = Selector.open()
            deviceToNetworkUDPQueue = ConcurrentLinkedQueue()
            deviceToNetworkTCPQueue = ConcurrentLinkedQueue()
            networkToDeviceQueue = ConcurrentLinkedQueue()
            udpSelectorLock = ReentrantLock()
            tcpSelectorLock = ReentrantLock()
            executorService = Executors.newFixedThreadPool(5).apply {
                submit(
                    UDPInput(
                        networkToDeviceQueue,
                        udpSelector,
                        udpSelectorLock
                    )
                )
                submit(UDPOutput(deviceToNetworkUDPQueue, networkToDeviceQueue, udpSelector, udpSelectorLock, this@LocalDNSService))
                submit(
                    TCPInput(
                        networkToDeviceQueue,
                        tcpSelector,
                        tcpSelectorLock
                    )
                )
                submit(
                    TCPOutput(
                        deviceToNetworkTCPQueue,
                        networkToDeviceQueue,
                        tcpSelector,
                        tcpSelectorLock,
                        this@LocalDNSService
                    )
                )
                submit(VPNRunnable(vpnInterface!!.fileDescriptor, deviceToNetworkUDPQueue!!, deviceToNetworkTCPQueue!!, networkToDeviceQueue!!))
            }

            isRunning = true

            Log.i(TAG, "Started")
        } catch (e: Exception) {
            Log.e(TAG, "Error starting service", e)
            stopVPNService()
        }
    }

    private fun stopVPNService() {
        clearResources()
        setNotification(false)
        stopSelf()
    }

    private fun setNotification(show: Boolean) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        try {
            val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            if (show) {
                manager.cancelAll()
                val channel = NotificationChannel("local_dns_channel_id", "LocalDNS", NotificationManager.IMPORTANCE_HIGH)
                manager.createNotificationChannel(channel)
                val notification = Notification.Builder(this, "local_dns_channel_id")
                    .setSmallIcon(R.drawable.mini_icon)
                    .setContentTitle("LocalDNS is working")
                    .build()
                startForeground(1, notification)
            } else {
                stopForeground(true)
                manager.cancelAll()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

}
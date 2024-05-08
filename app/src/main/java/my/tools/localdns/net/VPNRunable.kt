package my.tools.localdns.net

import android.util.Log
import java.io.*
import java.lang.Exception
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentLinkedQueue


class VPNRunnable (
    private val vpnFileDescriptor: FileDescriptor,
    private val deviceToNetworkUDPQueue: ConcurrentLinkedQueue<Packet>,
    private val deviceToNetworkTCPQueue: ConcurrentLinkedQueue<Packet>,
    private val networkToDeviceQueue: ConcurrentLinkedQueue<ByteBuffer>
) : Runnable {

    companion object {
        private const val TAG = "VPNRunnable"
    }

    override fun run() {
        Log.i(TAG, "Start")
        val vpnInput = FileInputStream(vpnFileDescriptor).channel
        val vpnOutput = FileOutputStream(vpnFileDescriptor).channel
        try {
            var bufferToNetwork: ByteBuffer? = null
            var dataSent = true
            var dataReceived: Boolean
            while (!Thread.interrupted()) {

                if (dataSent) {
                    bufferToNetwork =
                        ByteBufferPool.acquire()
                } else {
                    bufferToNetwork?.clear()
                }

                val readBytes = vpnInput.read(bufferToNetwork)
                if (readBytes > 0) {
                    dataSent = true
                    bufferToNetwork!!.flip()
                    val packet = Packet(bufferToNetwork)
                    when {
                        packet.isUDP -> deviceToNetworkUDPQueue.offer(packet)
                        packet.isTCP -> deviceToNetworkTCPQueue.offer(packet)
                        else -> {
                            Log.w(TAG, "Unknown packet type")
                            dataSent = false
                        }
                    }
                } else {
                    dataSent = false
                }

                val bufferFromNetwork = networkToDeviceQueue.poll()
                if (bufferFromNetwork != null) {
                    bufferFromNetwork.flip()
                    while (bufferFromNetwork.hasRemaining()) try {
                        vpnOutput.write(bufferFromNetwork)
                    } catch (e: Exception) {
                        Log.e(TAG, e.toString(), e)
                        break
                    }
                    dataReceived = true
                    ByteBufferPool.release(
                        bufferFromNetwork
                    )
                } else {
                    dataReceived = false
                }

                if (!dataSent && !dataReceived) Thread.sleep(11)
            }
        } catch (e: InterruptedException) {
            Log.i(TAG, "Stopping")
        } catch (e: IOException) {
            Log.w(TAG, e.toString(), e)
        } finally {
            closeResources(vpnInput, vpnOutput)
        }
    }

    private fun closeResources(vararg resources: Closeable?) {
        for (resource in resources) {
            try {
                resource?.close()
            } catch (e: Exception) {
                Log.e(TAG, e.toString(), e)
            }
        }
    }
}
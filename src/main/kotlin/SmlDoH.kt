import doh.DoHClient
import kotlinx.coroutines.runBlocking
import java.io.InputStream
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.net.ServerSocket
import java.net.Socket
import java.util.*
import java.util.regex.Pattern

class SmlDoH(
    private val proxyAddress: String = "127.0.0.1",
    private var proxyPort: Int = 4525,
    private val dohURL: String = "https://sky.rethinkdns.com/",
    private val numFragment: Int = 300,
    private val fragmentSleep: Double = 0.001,
    private var offlineDns:Map<String,String> = mapOf(),//must be lowercase
    private var debugMode:Boolean = false
) : Thread() {
    private var serverSocket: ServerSocket? = null
    private var clientSocket: Socket? = null
    private var isReady = false

    companion object {
        fun isValidIPAddress(ip: String?): Boolean {
            if (ip == null) {
                return false
            }
            val zeroTo255 = "(\\d{1,2}|(0|1)\\" + "d{2}|2[0-4]\\d|25[0-5])"
            val regex = "$zeroTo255\\.$zeroTo255\\.$zeroTo255\\.$zeroTo255"
            val p = Pattern.compile(regex)
            val m = p.matcher(ip)
            return m.matches()
        }

        fun pickKRandomInts(k: Int, n: Int): List<Int> {
            var k1 = k
            if (k1 > n) {
                k1 = n - 1
            }
            val numbers: MutableList<Int> = ArrayList()
            for (i in 1..n) {
                numbers.add(i)
            }
            numbers.shuffle()
            val result: MutableList<Int> = ArrayList()
            for (i in 0 until k1) {
                result.add(numbers[i])
            }
            result.sort()
            return result
        }
    }

    override fun run() {
        try {
            serverSocket = ServerSocket(proxyPort)
            proxyPort = serverSocket!!.localPort
            println("HTTPS Listening at $proxyAddress:$proxyPort")
            isReady = true
            while (true) {
                if (debugMode) {
                    println("Waiting for input socket ...")
                }
                clientSocket = serverSocket!!.accept()
                val upstreamThread = Upstream(clientSocket)
                upstreamThread.start()
            }
        } catch (e: Exception) {
            if (debugMode) {
                println("Server Error: " + e.message)
            }
            isReady = false
        } finally {
            if (debugMode) {
                println("Server Stopped. Listening Finished.")
            }
            isReady = false
        }
        println("Server Thread Finished")
    }

    fun safelyStopServer() {
        try {
            serverSocket!!.close()
        } catch (e: Exception) {
            if (debugMode) {
                println("Safe Stop Error: " + e.message)
            }
            return
        }
        println("Server socket safely stopped")
    }
    inner class Upstream(
        private var clientSocket: Socket?,
    ) : Thread() {
        private var inputStream: InputStream? = null
        private var os: OutputStream? = null
        private var backendSocket: Socket? = null
        private var buffer: ByteArray = ByteArray(8192)
        private var b = 0
        private var firstFlag: Boolean = true
        private var fragmentSleepMillisecond: Long = (fragmentSleep * 1000).toLong()
        private var firstTimeSleep: Long = 100 // wait 100 millisecond for first packet to fully receive


        override fun run() = runBlocking {
            try {
                backendSocket = handleClientRequest(clientSocket)
                if (backendSocket == null) {
                    clientSocket!!.close()
                    return@runBlocking
                }
                val downThread = Downstream(backendSocket!!.getInputStream(), clientSocket!!.getOutputStream())
                downThread.start()
                inputStream = clientSocket!!.getInputStream()
                os = backendSocket!!.getOutputStream()
                if (debugMode) {
                    println("up-stream started")
                }
                sleep(firstTimeSleep) // wait n millisecond for first packet to fully receive
                while (inputStream!!.read(buffer).also { b = it } != -1) {
                    if (firstFlag) {
                        firstFlag = false
                        sendDataInFragment()
                    } else {
                        os!!.write(buffer, 0, b)
                        os!!.flush()
                    }
                }
            } catch (e: Exception) {
                if (debugMode) {
                    println("up-stream: " + e.message)
                }
            } finally {
                if (debugMode) {
                    println("up-stream finished")
                }
            }
            safelyCloseSocket(clientSocket)
            safelyCloseSocket(backendSocket)
            try {
                os!!.flush()
                os!!.close()
                inputStream!!.close()
            } catch (e: Exception) {
                if (debugMode) {
                    println("up-stream Close Error: " + e.message)
                }
            }
        }

        private suspend fun handleClientRequest(cliSocket: Socket?): Socket? {
            var remoteHost: String?
            val remotePort: Int
            val backendSocket: Socket
            var responseData: String
            val clientSocketInputStream: InputStream
            var outputStreamWriter: OutputStreamWriter? = null

            return try {
                clientSocketInputStream = cliSocket!!.getInputStream()
                outputStreamWriter = OutputStreamWriter(cliSocket.getOutputStream())
                sleep(10) //wait 10 millisecond to fully receive packet from client
                b = clientSocketInputStream.read(buffer)
                val data = String(buffer, 0, b)
                val requestLines = data.split("\r\n".toRegex()).dropLastWhile { it.isEmpty() }.toTypedArray()
                val requestParts = requestLines[0].split(" ".toRegex()).dropLastWhile { it.isEmpty() }.toTypedArray()
                val rMethod = requestParts[0]
                val rHost = requestParts[1]
                when (rMethod) {
                    "CONNECT" -> {
                        val hp = rHost.split(":".toRegex()).dropLastWhile { it.isEmpty() }.toTypedArray()
                        remoteHost = hp[0]
                        remotePort = hp[1].toInt()
                    }
                    "GET", "POST", "HEAD", "OPTIONS", "PUT", "DELETE", "PATCH", "TRACE" -> {
                        val qUrl = rHost.replace("http://", "https://")
                        if (debugMode) {
                            println("redirect to HTTPS (302) $qUrl")
                        }
                        responseData = "HTTP/1.1 302 Found\r\nLocation: $qUrl\r\nProxy-agent: MyProxy/1.0\r\n\r\n"
                        outputStreamWriter.write(responseData)
                        outputStreamWriter.flush()
                        cliSocket.close()
                        return null
                    }
                    else -> {
                        if (debugMode) {
                            println("Unknown method Error 400 : $rMethod")
                        }
                        responseData = "HTTP/1.1 400 Bad Request\r\nProxy-agent: MyProxy/1.0\r\n\r\n"
                        outputStreamWriter.write(responseData)
                        outputStreamWriter.flush()
                        cliSocket.close()
                        return null
                    }
                }
                if (!isValidIPAddress(remoteHost)) {
                    if (debugMode) {
                        println("query DoH --> $remoteHost")
                    }
                    val isDoh = dohURL.contains(remoteHost,true)
                    val offlineIp = offlineDns[remoteHost.lowercase()]
                    if (offlineIp != null){
                        remoteHost = offlineIp
                    }else {
                        if(!isDoh){
                            val doh = DoHClient(dohURL,"$proxyAddress:$proxyPort")
                            doh.use {
                                remoteHost = doh.lookUp(remoteHost!!, "A").data[0]
                            }
                        }
                    }
                }
                if (debugMode) {
                    println("$remoteHost --> $remotePort")
                }
                backendSocket = Socket(remoteHost, remotePort)
                backendSocket.tcpNoDelay = true
                responseData = "HTTP/1.1 200 Connection established\r\nProxy-agent: MyProxy/1.0\r\n\r\n"
                outputStreamWriter.write(responseData)
                outputStreamWriter.flush()
                backendSocket
            } catch (e: Exception) {
                if (debugMode) {
                    println("Handle client Req Error 502: " + e.message)
                }
                responseData = "HTTP/1.1 502 Bad Gateway (is IP filtered?)\r\nProxy-agent: MyProxy/1.0\r\n\r\n"
                try {
                    outputStreamWriter!!.write(responseData)
                    outputStreamWriter.flush()
                    cliSocket!!.close()
                } catch (e2: Exception) {
                    if (debugMode) {
                        println("Handle client write 502 Error: " + e2.message)
                    }
                }
                null
            }
        }

        private fun safelyCloseSocket(sock: Socket?) {
            try {
                if (sock != null) {
                    if (sock.isConnected || !sock.isClosed) {
                        sock.shutdownInput()
                        sock.shutdownOutput()
                        sock.close()
                    }
                }
            } catch (e: Exception) {
                println("Socket Close Error: " + e.message)
            }
        }

        private fun sendDataInFragment() {
            try {
                val l = b
                val indices = pickKRandomInts(numFragment - 1, l)
                var jPre = 0
                var jNext: Int
                for (i in indices.indices) {
                    jNext = indices[i]
                    //if (debugMode) {
                    // println("send from "+ jPre + " to "+ jNext);
                    //}
                    os!!.write(buffer, jPre, jNext - jPre)
                    os!!.flush()
                    sleep(fragmentSleepMillisecond)
                    jPre = jNext
                }
                //if (debugMode) {
                // println("send from "+ jPre + " to "+ l);
                //}
                os!!.write(buffer, jPre, l - jPre)
                os!!.flush()
            } catch (e: Exception) {
                if (debugMode) {
                    println("Error in fragment function: " + e.message)
                }
                return
            }
        }

    }

    inner class Downstream(
        private var inputStream: InputStream,
        private var os: OutputStream
    ) : Thread() {
        private var buffer: ByteArray = ByteArray(4096)
        private var b = 0

        override fun run() {
            try {
                if (debugMode) {
                    println("down-stream started")
                }
                while (inputStream.read(buffer).also { b = it } != -1) {
                    os.write(buffer, 0, b)
                    os.flush()
                }
            } catch (e: Exception) {
                if (debugMode) {
                    println("down-stream: " + e.message)
                }
            } finally {
                if (debugMode) {
                    println("down-stream finished")
                }
            }
            try {
                os.flush()
                os.close()
                inputStream.close()
            } catch (e: Exception) {
                if (debugMode) {
                    println("Stream Close Error: " + e.message)
                }
            }
        }
    }
}

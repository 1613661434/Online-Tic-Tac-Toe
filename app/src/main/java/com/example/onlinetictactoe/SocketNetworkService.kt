package com.example.onlinetictactoe

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket

class SocketNetworkService(
    private val onGameUpdate: (GameUpdate) -> Unit,
    private val getCurrentRoomId: () -> String?
) {
    companion object {
        private const val TAG = "SocketNetworkService"
        private const val PORT_RANGE_START = 8080
        private const val PORT_RANGE_END = 8090
        private const val SOCKET_TIMEOUT = 5000
        private const val RECONNECT_DELAY = 2000L
    }

    private var serverSocket: ServerSocket? = null
    private var clientSocket: Socket? = null
    private var serverJob: Job? = null
    private var clientJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO)

    private val gson = com.google.gson.Gson()

    // 新增：保存客户端输出流（房主用，用于向加入方发送消息）
    private val clientOutputs = mutableListOf<OutputStreamWriter>()

    // 获取本地IP地址
    fun getLocalIpAddress(): String {
        val en = NetworkInterface.getNetworkInterfaces()
        while (en.hasMoreElements()) {
            val intf = en.nextElement()
            val enumIpAddr = intf.inetAddresses
            while (enumIpAddr.hasMoreElements()) {
                val inetAddress = enumIpAddr.nextElement()
                // 排除回环地址和IPv6地址
                if (!inetAddress.isLoopbackAddress && inetAddress is Inet4Address) {
                    return inetAddress.hostAddress ?: "127.0.0.1"
                }
            }
        }
        return "127.0.0.1"
    }

    // 启动Socket服务器
    fun startServer(onPortAssigned: (Int) -> Unit = {}): Int {
        return try {
            for (port in PORT_RANGE_START..PORT_RANGE_END) {
                try {
                    serverSocket = ServerSocket(port)
                    Log.d(TAG, "Socket服务器启动在端口: $port")

                    serverJob = scope.launch {
                        try {
                            while (true) {
                                val socket = serverSocket?.accept()
                                if (socket != null) {
                                    handleIncomingConnection(socket)
                                }
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Socket服务器异常", e)
                        }
                    }

                    onPortAssigned(port)
                    return port
                } catch (e: java.net.BindException) {
                    Log.d(TAG, "端口 $port 被占用，尝试下一个")
                    continue
                }
            }
            throw Exception("没有可用端口")
        } catch (e: Exception) {
            Log.e(TAG, "启动服务器失败", e)
            throw e
        }
    }

    // 连接到对方服务器
    suspend fun connectToServer(ip: String, port: Int): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                // 先关闭已有连接
                disconnect()

                val socket = Socket()
                socket.soTimeout = SOCKET_TIMEOUT
                val address = InetAddress.getByName(ip)
                socket.connect(java.net.InetSocketAddress(address, port), SOCKET_TIMEOUT)

                clientSocket = socket
                startClientListener(socket)

                Log.d(TAG, "已连接到服务器: $ip:$port")
                true
            } catch (e: Exception) {
                Log.e(TAG, "连接服务器失败: ${e.message}")
                false
            }
        }
    }

    // 发送游戏更新
    // 发送游戏更新（区分客户端和服务器端发送逻辑）
    suspend fun sendGameUpdate(update: GameUpdate): Boolean {
        return withContext(Dispatchers.IO) {
            // 客户端（加入方）发送：用clientSocket
            if (clientSocket != null) {
                try {
                    val socket = clientSocket ?: return@withContext false
                    val writer = OutputStreamWriter(socket.getOutputStream(), "UTF-8")
                    val json = gson.toJson(update)
                    writer.write("$json\n")
                    writer.flush()
                    Log.d(TAG, "客户端发送更新: ${update.roomId}")
                    true
                } catch (e: Exception) {
                    Log.e(TAG, "客户端发送失败", e)
                    attemptReconnect()
                    false
                }
            }
            // 服务器（房主）发送：用保存的clientOutputs
            else if (serverSocket != null && clientOutputs.isNotEmpty()) {
                try {
                    val json = gson.toJson(update)
                    clientOutputs.forEach { writer ->
                        writer.write("$json\n")
                        writer.flush()
                    }
                    Log.d(TAG, "房主发送更新: ${update.roomId}")
                    true
                } catch (e: Exception) {
                    Log.e(TAG, "房主发送失败", e)
                    false
                }
            } else {
                Log.w(TAG, "无可用连接发送消息")
                false
            }
        }
    }

    // 检查房间是否存在
    suspend fun checkRoomExists(ip: String, port: Int, roomId: String): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val socket = Socket()
                socket.soTimeout = 3000
                val address = InetAddress.getByName(ip)
                socket.connect(java.net.InetSocketAddress(address, port), 3000)

                // 发送房间检查请求
                val writer = OutputStreamWriter(socket.getOutputStream(), "UTF-8")
                val reader = BufferedReader(InputStreamReader(socket.getInputStream(), "UTF-8"))

                val checkRequest = RoomCheckRequest(roomId)
                val json = gson.toJson(checkRequest)

                writer.write("CHECK:$json\n")
                writer.flush()

                // 读取响应
                val response = reader.readLine()
                socket.close()

                if (response != null) {
                    val result = gson.fromJson(response, RoomCheckResponse::class.java)
                    return@withContext result.exists
                }
                false
            } catch (e: Exception) {
                Log.e(TAG, "检查房间失败", e)
                false
            }
        }
    }

    // 处理传入连接
    private fun handleIncomingConnection(socket: Socket) {
        scope.launch {
            var writer: OutputStreamWriter? = null
            try {
                val reader = BufferedReader(InputStreamReader(socket.getInputStream(), "UTF-8"))
                writer = OutputStreamWriter(socket.getOutputStream(), "UTF-8")

                // 新增：保存客户端输出流（房主需要用它发消息）
                clientOutputs.add(writer)

                while (true) {
                    val message = reader.readLine() ?: break
                    Log.d(TAG, "收到消息: $message")

                    if (message.startsWith("CHECK:")) {
                        // 处理房间检查（原有逻辑不变）
                        val json = message.removePrefix("CHECK:")
                        val request = gson.fromJson(json, RoomCheckRequest::class.java)
                        val exists = request.roomId == getCurrentRoomId()
                        val response = RoomCheckResponse(exists)
                        writer.write("${gson.toJson(response)}\n")
                        writer.flush()
                    } else {
                        // 处理游戏更新（原有逻辑不变）
                        try {
                            val update = gson.fromJson(message, GameUpdate::class.java)
                            onGameUpdate(update)
                        } catch (e: Exception) {
                            Log.e(TAG, "解析更新失败", e)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "连接异常", e)
            } finally {
                // 新增：移除失效的输出流
                writer?.let { clientOutputs.remove(it) }
                try {
                    socket.close()
                } catch (e: Exception) {
                    Log.e(TAG, "关闭Socket异常", e)
                }
            }
        }
    }

    // 启动客户端监听器
    private fun startClientListener(socket: Socket) {
        clientJob = scope.launch {
            try {
                val reader = BufferedReader(InputStreamReader(socket.getInputStream(), "UTF-8"))

                while (true) {
                    val message = reader.readLine() ?: break
                    Log.d(TAG, "从服务器收到: $message")

                    try {
                        val update = gson.fromJson(message, GameUpdate::class.java)
                        onGameUpdate(update)
                    } catch (e: Exception) {
                        Log.e(TAG, "解析消息失败", e)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "客户端监听异常", e)
            }
        }
    }

    // 尝试重新连接
    private suspend fun attemptReconnect() {
        // 实现重连逻辑
        delay(RECONNECT_DELAY)
    }

    // 断开连接
    fun disconnect() {
        try {
            clientJob?.cancel()
            clientJob = null

            clientSocket?.close()
            clientSocket = null

            serverJob?.cancel()
            serverJob = null

            serverSocket?.close()
            serverSocket = null

            // 新增：清空客户端输出流
            clientOutputs.clear()

            Log.d(TAG, "断开所有连接")
        } catch (e: Exception) {
            Log.e(TAG, "断开连接异常", e)
        }
    }

    data class RoomCheckRequest(val roomId: String)
    data class RoomCheckResponse(val exists: Boolean)
}
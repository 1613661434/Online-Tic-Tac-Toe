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
import java.net.InetAddress
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

    // 获取本地IP地址
    fun getLocalIpAddress(): String {
        return try {
            val interfaces = java.net.NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val networkInterface = interfaces.nextElement()
                val addresses = networkInterface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val address = addresses.nextElement()
                    if (!address.isLoopbackAddress && address.hostAddress?.indexOf(':') == -1) {
                        return address.hostAddress
                    }
                }
            }
            "127.0.0.1"
        } catch (e: Exception) {
            Log.e(TAG, "获取本地IP失败", e)
            "127.0.0.1"
        }
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
    suspend fun sendGameUpdate(update: GameUpdate): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val socket = clientSocket ?: return@withContext false
                val writer = OutputStreamWriter(socket.getOutputStream(), "UTF-8")
                val json = gson.toJson(update)

                writer.write("$json\n")
                writer.flush()

                Log.d(TAG, "发送游戏更新: ${update.roomId}")
                true
            } catch (e: Exception) {
                Log.e(TAG, "发送游戏更新失败", e)
                // 尝试重新连接
                attemptReconnect()
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
            try {
                val reader = BufferedReader(InputStreamReader(socket.getInputStream(), "UTF-8"))
                val writer = OutputStreamWriter(socket.getOutputStream(), "UTF-8")

                while (true) {
                    val message = reader.readLine() ?: break

                    Log.d(TAG, "收到消息: $message")

                    if (message.startsWith("CHECK:")) {
                        // 处理房间检查
                        val json = message.removePrefix("CHECK:")
                        val request = gson.fromJson(json, RoomCheckRequest::class.java)

                        val exists = request.roomId == getCurrentRoomId()
                        val response = RoomCheckResponse(exists)

                        writer.write("${gson.toJson(response)}\n")
                        writer.flush()
                    } else {
                        // 处理游戏更新
                        try {
                            val update = gson.fromJson(message, GameUpdate::class.java)
                            onGameUpdate(update)
                        } catch (e: Exception) {
                            Log.e(TAG, "解析游戏更新失败", e)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "处理连接异常", e)
            } finally {
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

            Log.d(TAG, "断开所有连接")
        } catch (e: Exception) {
            Log.e(TAG, "断开连接异常", e)
        }
    }

    data class RoomCheckRequest(val roomId: String)
    data class RoomCheckResponse(val exists: Boolean)
}

// 游戏更新数据类
data class GameUpdate(
    val roomId: String,
    val type: UpdateType, // 更新类型
    val board: List<List<CellState>>,
    val currentPlayer: CellState,
    val gameResult: GameResult,
    val playerName: String? = null, // 玩家名称（用于加入房间）
    val isHost: Boolean = false // 是否是房主
)

// 更新类型枚举
enum class UpdateType {
    JOIN,      // 玩家加入
    MOVE,      // 落子
    RESET,     // 重置游戏
    DISCONNECT // 断开连接
}
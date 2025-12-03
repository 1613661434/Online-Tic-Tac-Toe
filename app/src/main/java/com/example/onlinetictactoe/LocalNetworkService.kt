package com.example.onlinetictactoe

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation as ClientContentNegotiation
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.serialization.gson.gson
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.engine.ApplicationEngine
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation as ServerContentNegotiation
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import java.net.InetAddress
import java.net.NetworkInterface
import java.util.Enumeration

class LocalNetworkService(private val onGameUpdate: (GameUpdate) -> Unit) {
    private var server: ApplicationEngine? = null

    // 客户端初始化（使用客户端专用ContentNegotiation）
    val client = HttpClient(CIO) {
        install(ClientContentNegotiation) {
            gson() // 配置Gson序列化
        }
    }

    // 获取本地局域网IP地址（过滤回环和IPv6）
    fun getLocalIpAddress(): String {
        val interfaces: Enumeration<NetworkInterface> = NetworkInterface.getNetworkInterfaces()
        while (interfaces.hasMoreElements()) {
            val networkInterface: NetworkInterface = interfaces.nextElement()
            val addresses: Enumeration<InetAddress> = networkInterface.inetAddresses
            while (addresses.hasMoreElements()) {
                val address: InetAddress = addresses.nextElement()
                if (!address.isLoopbackAddress && address.hostAddress?.indexOf(':') == -1) {
                    return address.hostAddress ?: "127.0.0.1"
                }
            }
        }
        return "127.0.0.1"
    }

    // 启动本地服务器（返回服务器IP）
    fun startServer(port: Int = 8080): String {
        // 直接在embeddedServer中配置Application，避免configure函数冲突
        server = embeddedServer(Netty, port = port, host = "0.0.0.0") {
            // 安装服务器端内容协商插件
            install(ServerContentNegotiation) {
                gson() // 与客户端保持一致的序列化方式
            }
            // 配置路由
            routing {
                post("/gameUpdate") {
                    val update = call.receive<GameUpdate>() // 接收对方的游戏更新
                    onGameUpdate(update) // 回调给ViewModel处理
                    call.respond(GameUpdateResponse(success = true))
                }
            }
        }.start(wait = false) // 非阻塞启动
        return getLocalIpAddress()
    }

    // 停止服务器
    fun stopServer() {
        server?.stop(gracePeriodMillis = 1000, timeoutMillis = 1000)
    }

    // 发送游戏更新到对手（需在协程中调用）
    suspend fun sendGameUpdate(ip: String, port: Int, update: GameUpdate) {
        client.post("http://$ip:$port/gameUpdate") {
            contentType(ContentType.Application.Json)
            setBody(update) // 发送本地游戏状态
        }
    }
}

// 网络传输的数据类（落子位置、玩家、游戏结果等）
data class GameUpdate(
    val roomId: String,
    val row: Int,
    val col: Int,
    val player: CellState,
    val gameResult: GameResult
)

// 服务器响应数据类
data class GameUpdateResponse(val success: Boolean)
package com.example.onlinetictactoe

import android.app.Application
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import io.ktor.client.call.body
import io.ktor.client.request.get
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class TicTacToeMviViewModel(application: Application) : AndroidViewModel(application) {
    // 安全获取Context（避免泄漏）
    private val context: Context = application.applicationContext
    // 网络服务
    private val apiService = ApiService.create()
    // Gson解析（JSON）
    private val gson = Gson()
    // 配置文件路径（文件处理）
    private val configFile = File(context.filesDir, "game_config.json")
    // 游戏记录（内存存储，应用重启丢失）
    private val _memoryRecords = mutableListOf<String>()
    // 时间格式化
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())

    // MVI核心：状态流
    val _uiState = MutableStateFlow(TicTacToeState())
    val uiState: StateFlow<TicTacToeState> = _uiState.asStateFlow()

    // 处理所有Intent（唯一逻辑入口）
    fun handleIntent(intent: TicTacToeIntent) {
        when (intent) {
            is TicTacToeIntent.ClickCell -> handleCellClick(intent.row, intent.col)
            TicTacToeIntent.ResetGame -> resetGame()
            TicTacToeIntent.CheckWinner -> checkWinner()
            is TicTacToeIntent.SelectGameMode -> selectGameMode(intent.mode)
            TicTacToeIntent.StartOnlineMatch -> startOnlineMatch()
            TicTacToeIntent.CancelOnlineMatch -> cancelOnlineMatch()
            TicTacToeIntent.LoadGameRecords -> loadGameRecords()
            TicTacToeIntent.ClearGameRecords -> clearGameRecords()
            is TicTacToeIntent.SaveConfig -> saveConfig(intent.mode, intent.boardSize)
            TicTacToeIntent.LoadConfig -> loadConfig()
            is TicTacToeIntent.CreateRoom -> createRoom(intent.playerName)
            is TicTacToeIntent.JoinRoom -> joinRoom(intent.roomId)
            TicTacToeIntent.ShareRoomLink -> shareRoomLink()
            is TicTacToeIntent.ExitRoom -> exitRoom()
            is TicTacToeIntent.ShowError -> {
                _uiState.update { it.copy(errorMsg = intent.message) }
            }
        }
    }

    private fun handleCellClick(row: Int, col: Int) {
        val currentBoard = _uiState.value.board
        // 检查是否可落子（非空、游戏进行中、非加载中）
        if (currentBoard[row][col] != CellState.EMPTY ||
            _uiState.value.gameResult != GameResult.PLAYING ||
            _uiState.value.isLoading) {
            return
        }

        // 更新本地棋盘（玩家落子）
        val newBoard = currentBoard.mapIndexed { r, rows ->
            rows.mapIndexed { c, cell ->
                if (r == row && c == col) _uiState.value.currentPlayer else cell
            }
        }

        val updatedState = _uiState.value.copy(
            board = newBoard,
            currentPlayer = if (_uiState.value.currentPlayer == CellState.X) CellState.O else CellState.X
        )
        _uiState.update { updatedState }

        // 检查胜负
        checkWinner()

        // 新增：如果是人机对战模式，且游戏仍在进行中，触发AI落子
        if (_uiState.value.gameMode == GameMode.HUMAN_VS_AI
            && _uiState.value.gameResult == GameResult.PLAYING) {
            aiMakeMove() // 调用AI落子逻辑
        }

        // 在线对战模式下，发送更新给对方
        if (_uiState.value.gameMode == GameMode.HUMAN_VS_HUMAN_ONLINE) {
            viewModelScope.launch(Dispatchers.IO) {
                val roomId = _uiState.value.currentRoom?.roomId ?: return@launch
                val currentState = _uiState.value
                // 构建包含完整棋盘和下一玩家的更新
                val gameUpdate = GameUpdate(
                    roomId = roomId,
                    oneWait = true,
                    gameResult = currentState.gameResult,
                    board = currentState.board,  // 发送完整棋盘
                    nextPlayer = if (currentState.currentPlayer == CellState.X) CellState.O else CellState.X  // 下一玩家
                )

                // 获取对方的IP和端口（修复连接信息获取方式）
                val currentRoomLink = _uiState.value.roomLink ?: return@launch
                val (remoteIp, port, _) = parseIpAndPortFromRoomId(currentRoomLink)
                if (remoteIp != null && port != null) {
                    try {
                        localNetworkService.sendGameUpdate(remoteIp, port, gameUpdate)
                    } catch (e: Exception) {
                        withContext(Dispatchers.Main) {
                            _uiState.update { it.copy(errorMsg = "发送失败：${e.message}") }
                        }
                    }
                }
            }
        }
    }

    // AI落子逻辑（简单的随机算法，可替换为更优算法）
    private fun aiMakeMove() {
        // AI开始思考，设置加载状态为true
        _uiState.update { it.copy(isLoading = true) }

        val board = _uiState.value.board
        val emptyCells = mutableListOf<Pair<Int, Int>>()

        // 找出所有空单元格
        for (row in board.indices) {
            for (col in board[row].indices) {
                if (board[row][col] == CellState.EMPTY) {
                    emptyCells.add(Pair(row, col))
                }
            }
        }

        // 如果有空格，随机选择一个落子
        if (emptyCells.isNotEmpty()) {
            val randomCell = emptyCells.random()
            // 使用withContext确保在主线程更新UI
            viewModelScope.launch(Dispatchers.Main) {
                // 模拟AI思考延迟
                delay(800)
                handleAICellClick(randomCell.first, randomCell.second)
                // AI落子完成，恢复加载状态
                _uiState.update { it.copy(isLoading = false) }
            }
        } else {
            // 没有空单元格，恢复加载状态
            _uiState.update { it.copy(isLoading = false) }
        }
    }

    // AI专用的落子方法，不会触发AI再次落子
    private fun handleAICellClick(row: Int, col: Int) {
        val currentBoard = _uiState.value.board
        if (currentBoard[row][col] != CellState.EMPTY || _uiState.value.gameResult != GameResult.PLAYING) {
            return
        }

        // 更新棋盘（AI落子）
        val newBoard = currentBoard.mapIndexed { r, rows ->
            rows.mapIndexed { c, cell ->
                if (r == row && c == col) _uiState.value.currentPlayer else cell
            }
        }

        _uiState.update {
            it.copy(
                board = newBoard,
                currentPlayer = if (it.currentPlayer == CellState.X) CellState.O else CellState.X
            )
        }

        // 检查胜负
        checkWinner()
    }

    // 重置游戏（修复boardSize引用冲突）
    private fun resetGame() {
        val updatedState = _uiState.value.copy(
            board = List(_uiState.value.boardSize) { List(_uiState.value.boardSize) { CellState.EMPTY } },
            currentPlayer = CellState.X, // 重置为初始玩家（例如X先开始）
            gameResult = GameResult.PLAYING,
            errorMsg = null
        )
        _uiState.update { updatedState }

        // 在线模式下，发送重置更新给对方
        if (_uiState.value.gameMode == GameMode.HUMAN_VS_HUMAN_ONLINE) {
            viewModelScope.launch(Dispatchers.IO) {
                val roomId = _uiState.value.currentRoom?.roomId ?: return@launch
                val currentRoomLink = _uiState.value.roomLink ?: return@launch
                val (remoteIp, port, _) = parseIpAndPortFromRoomId(currentRoomLink)
                if (remoteIp != null && port != null) {
                    try {
                        // 构建重置更新（包含空棋盘和初始玩家）
                        val resetUpdate = GameUpdate(
                            roomId = roomId,
                            oneWait = true,
                            gameResult = GameResult.PLAYING,
                            board = updatedState.board, // 空棋盘
                            nextPlayer = updatedState.currentPlayer // 初始玩家（X）
                        )
                        localNetworkService.sendGameUpdate(remoteIp, port, resetUpdate)
                    } catch (e: Exception) {
                        withContext(Dispatchers.Main) {
                            _uiState.update { it.copy(errorMsg = "重置同步失败：${e.message}") }
                        }
                    }
                }
            }
        }
    }

    // 检查胜负逻辑
    private fun checkWinner() {
        val board = _uiState.value.board
        val size = board.size
        var winner: CellState? = null

        // 检查行
        for (row in 0 until size) {
            if (board[row].all { it == board[row][0] && it != CellState.EMPTY }) {
                winner = board[row][0]
            }
        }

        // 检查列
        if (winner == null) {
            for (col in 0 until size) {
                var allSame = true
                val first = board[0][col]
                if (first == CellState.EMPTY) continue
                for (row in 1 until size) {
                    if (board[row][col] != first) {
                        allSame = false
                        break
                    }
                }
                if (allSame) winner = first
            }
        }

        // 检查对角线
        if (winner == null) {
            var allSame = true
            val first = board[0][0]
            if (first != CellState.EMPTY) {
                for (i in 1 until size) {
                    if (board[i][i] != first) {
                        allSame = false
                        break
                    }
                }
                if (allSame) winner = first
            }
        }

        // 检查反对角线
        if (winner == null) {
            var allSame = true
            val first = board[0][size - 1]
            if (first != CellState.EMPTY) {
                for (i in 1 until size) {
                    if (board[i][size - 1 - i] != first) {
                        allSame = false
                        break
                    }
                }
                if (allSame) winner = first
            }
        }

        // 更新结果
        val newResult = when (winner) {
            CellState.X -> GameResult.WIN_X
            CellState.O -> GameResult.WIN_O
            else -> {
                if (board.all { row -> row.all { it != CellState.EMPTY } }) {
                    GameResult.DRAW
                } else {
                    GameResult.PLAYING
                }
            }
        }

        _uiState.update {
            it.copy(gameResult = newResult)
        }

        // 游戏结束则保存记录到内存
        if (newResult != GameResult.PLAYING) {
            saveGameRecordToMemory(newResult)
        }
    }

    // 选择对战模式（修复boardSize引用冲突）
    private fun selectGameMode(mode: GameMode) {
        _uiState.update { state ->
            state.copy(
                gameMode = mode,
                currentScreen = Screen.GAME,
                board = List(state.boardSize) { List(state.boardSize) { CellState.EMPTY } },
                gameResult = GameResult.PLAYING
            )
        }
    }

    // 模拟在线匹配（网络请求+协程并发，修复安全调用冗余）
    private fun startOnlineMatch() {
        _uiState.update {
            it.copy(isMatching = true, isLoading = true, errorMsg = null)
        }

        // 协程发起网络请求（IO调度器）
        viewModelScope.launch(Dispatchers.IO) {
            try {
                // 模拟网络延迟
                delay(2000)
                // 发起匹配请求
                val response = apiService.startMatch(_uiState.value.gameMode.name)
                if (response.isSuccessful) {
                    val matchResponse = response.body()
                    if (matchResponse?.code == 200 && matchResponse.data != null) {
                        // 匹配成功（修复安全调用冗余）
                        withContext(Dispatchers.Main) {
                            _uiState.update {
                                it.copy(
                                    isMatching = false,
                                    isLoading = false,
                                    matchedOpponent = matchResponse.data.opponent,
                                    currentScreen = Screen.GAME
                                )
                            }
                        }
                    } else {
                        throw Exception(matchResponse?.msg ?: "匹配失败")
                    }
                } else {
                    throw Exception("网络请求失败：${response.code()}")
                }
            } catch (e: Exception) {
                // 匹配失败
                withContext(Dispatchers.Main) {
                    _uiState.update {
                        it.copy(
                            isMatching = false,
                            isLoading = false,
                            errorMsg = e.message
                        )
                    }
                }
            }
        }
    }

    // 取消在线匹配
    private fun cancelOnlineMatch() {
        _uiState.update {
            it.copy(isMatching = false, isLoading = false, errorMsg = null)
        }
    }

    // 加载内存中的游戏记录
    private fun loadGameRecords() {
        _uiState.update { it.copy(gameRecords = _memoryRecords.toList()) }
    }

    // 清空内存中的游戏记录
    private fun clearGameRecords() {
        _memoryRecords.clear()
        _uiState.update { it.copy(gameRecords = emptyList()) }
    }

    // 保存游戏记录到内存
    private fun saveGameRecordToMemory(result: GameResult) {
        val time = dateFormat.format(Date())
        val mode = _uiState.value.gameMode.name
        val resultStr = result.name
        val record = "$time | 模式：$mode | 结果：$resultStr"
        _memoryRecords.add(0, record) // 最新记录放最前面
        // 更新状态
        _uiState.update { it.copy(gameRecords = _memoryRecords.toList()) }
    }

    // 保存配置到文件（文件处理核心）
    private fun saveConfig(mode: GameMode, boardSize: Int) {
        viewModelScope.launch(Dispatchers.IO) {
            val config = mapOf(
                "mode" to mode.name,
                "boardSize" to boardSize
            )
            // 写入JSON文件
            FileWriter(configFile).use { writer ->
                gson.toJson(config, writer)
            }
            // 更新状态
            withContext(Dispatchers.Main) {
                _uiState.update {
                    it.copy(savedMode = mode, boardSize = boardSize)
                }
            }
        }
    }
    // 加载配置文件
    private fun loadConfig() {
        viewModelScope.launch(Dispatchers.IO) {
            // 定义默认值（你根据实际业务改这两个值即可）
            val defaultMode = GameMode.HUMAN_VS_AI
            val defaultBoardSize = 3 // 替换成你的默认棋盘大小（比如4/5）

            try {
                if (configFile.exists()) {
                    // 文件存在：解析配置
                    val configJson = configFile.readText()
                    // 修复TypeToken语法，确保Gson解析泛型Map不报错
                    val configType = object : TypeToken<Map<String, Any?>>() {}.type
                    val config: Map<String, Any?> = gson.fromJson(configJson, configType)

                    // 安全解析mode（枚举）
                    val mode = config["mode"]?.toString()?.let { modeStr ->
                        runCatching { GameMode.valueOf(modeStr) }.getOrDefault(defaultMode)
                    } ?: defaultMode

                    // 安全解析boardSize（整数）
                    val boardSize = config["boardSize"]?.toString()?.let { sizeStr ->
                        runCatching { sizeStr.toInt() }.getOrDefault(defaultBoardSize)
                    } ?: defaultBoardSize

                    // 主线程更新状态
                    withContext(Dispatchers.Main) {
                        _uiState.update {
                            it.copy(savedMode = mode, boardSize = boardSize)
                        }
                    }
                } else {
                    // 关键：补充else分支（文件不存在时设默认值）
                    withContext(Dispatchers.Main) {
                        _uiState.update {
                            it.copy(savedMode = defaultMode, boardSize = defaultBoardSize)
                        }
                    }
                }
            } catch (e: Exception) {
                // 任何异常（读文件/解析/转换）都设默认值
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                    _uiState.update {
                        it.copy(savedMode = defaultMode, boardSize = defaultBoardSize)
                    }
                }
            }
        }
    }

    // 新增LocalNetworkService实例（全局唯一）
    private val localNetworkService = LocalNetworkService(
        onGameUpdate = { gameUpdate ->
            handleRemoteGameUpdate(gameUpdate)
        },
        getCurrentRoomId = {
            // 提供获取当前房间ID的逻辑
            _uiState.value.currentRoom?.roomId
        }
    )

    // 处理对方发送的游戏更新
    private fun handleRemoteGameUpdate(update: GameUpdate) {
        // 验证房间ID是否匹配
        if (update.roomId != _uiState.value.currentRoom?.roomId) return

        viewModelScope.launch(Dispatchers.Main) {
            // 处理"玩家加入"的特殊通知（用oneWait=false标识）
            if (!update.oneWait) {
                // 房主收到加入通知：更新为游戏状态，结束等待
                _uiState.update {
                    it.copy(
                        isWaitingForPlayer = false,
                        matchedOpponent = "对方玩家",  // 显示对手信息
                        currentRoom = it.currentRoom?.copy(isFull = true)  // 标记房间已满
                    )
                }
                return@launch  // 结束处理，不更新棋盘
            }

            // 处理正常落子更新：直接使用对方发送的完整棋盘
            _uiState.update {
                it.copy(
                    board = update.board,  // 同步完整棋盘
                    currentPlayer = update.nextPlayer,  // 更新为下一玩家
                    gameResult = update.gameResult,
                    isLoading = false
                )
            }

            // 游戏结束处理
            if (update.gameResult != GameResult.PLAYING) {
                saveGameRecordToMemory(update.gameResult)
            }
        }
    }

    // 在TicTacToeMviViewModel的createRoom函数中修改
    private fun createRoom(playerName: String) {
        _uiState.update { it.copy(isLoading = true) }
        viewModelScope.launch(Dispatchers.IO) {
            var port = 8080
            var maxRetries = 3
            var success = false

            while (maxRetries > 0 && !success) {
                try {
                    val localIp = localNetworkService.startServer(port)
                    val roomId = "local_${System.currentTimeMillis()}"
                    // 生成 "IP:端口:房间号" 格式的链接（核心修改）
                    val link = "$localIp:$port:$roomId"

                    val localRoom = GameRoom(
                        roomId = roomId,
                        host = playerName,
                        guest = null
                    )

                    withContext(Dispatchers.Main) {
                        _uiState.update {
                            it.copy(
                                currentRoom = localRoom,
                                roomLink = link,  // 保存新格式链接
                                isLoading = false,
                                currentScreen = Screen.GAME,
                                currentPlayer = CellState.O,
                                gameMode = GameMode.HUMAN_VS_HUMAN_ONLINE,
                                isWaitingForPlayer = true,
                                gameResult = GameResult.PLAYING
                            )
                        }
                    }
                    success = true
                } catch (e: Exception) {
                    // 保持原有重试逻辑不变
                    if (e.message?.contains("Address already in use") == true) {
                        maxRetries--
                        port++
                        if (maxRetries == 0) {
                            withContext(Dispatchers.Main) {
                                _uiState.update {
                                    it.copy(
                                        isLoading = false,
                                        errorMsg = "端口被占用，请稍后重试（已尝试端口: 8080-${port}）"
                                    )
                                }
                            }
                        } else {
                            delay(500)
                        }
                    } else {
                        withContext(Dispatchers.Main) {
                            _uiState.update {
                                it.copy(isLoading = false, errorMsg = "创建房间失败：${e.message}")
                            }
                        }
                        break
                    }
                }
            }
        }
    }

    // 处理加入房间
    private fun joinRoom(roomLink: String) {
        val (remoteIp, port, roomId) = parseIpAndPortFromRoomId(roomLink) ?: run {
            _uiState.update { it.copy(errorMsg = "无效的房间链接格式", isLoading = false) }
            return
        }
        // 验证必要参数
        if (remoteIp.isNullOrBlank() || port == null || roomId.isNullOrBlank()) {
            _uiState.update { it.copy(errorMsg = "链接缺少IP、端口或房间号", isLoading = false) }
            return
        }

        _uiState.update { it.copy(isLoading = true) }

        viewModelScope.launch(Dispatchers.IO) {
            try {
                // 检查房间是否存在
                val response = localNetworkService.client.get("http://$remoteIp:$port/checkRoom") {
                    url {
                        parameters.append("roomId", roomId)
                    }
                }

                val checkResponse = gson.fromJson(response.body<String>(), LocalNetworkService.RoomCheckResponse::class.java)

                if (checkResponse.exists) {
                    // 房间存在，进入游戏
                    withContext(Dispatchers.Main) {
                        val currentBoardSize = _uiState.value.boardSize
                        _uiState.update { state ->
                            state.copy(
                                currentPlayer = CellState.X,  // 加入者固定为X
                                currentRoom = GameRoom(
                                    roomId = roomId,
                                    host = "对方玩家",
                                    guest = "本地玩家",
                                    isFull = true
                                ),
                                isLoading = false,
                                currentScreen = Screen.GAME,
                                isWaitingForPlayer = false,
                                gameMode = GameMode.HUMAN_VS_HUMAN_ONLINE,
                                board = List(currentBoardSize) { List(currentBoardSize) { CellState.EMPTY } },
                                gameResult = GameResult.PLAYING
                            )
                        }
                    }

                    // 发送玩家加入通知给房主（关键修复）
                    val joinUpdate = GameUpdate(
                        roomId = roomId,
                        oneWait = false,
                        gameResult = GameResult.PLAYING,
                        // 补充空棋盘（使用当前棋盘大小）
                        board = List(_uiState.value.boardSize) { List(_uiState.value.boardSize) { CellState.EMPTY } },
                        // 房主下棋
                        nextPlayer = CellState.O
                    )
                    localNetworkService.sendGameUpdate(remoteIp, port, joinUpdate)
                } else {
                    // 房间不存在
                    withContext(Dispatchers.Main) {
                        _uiState.update {
                            it.copy(
                                isLoading = false,
                                errorMsg = "房间不存在或已关闭",
                                currentScreen = Screen.HOME
                            )
                        }
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            errorMsg = "无法连接到房间：${e.message}",
                            currentScreen = Screen.HOME
                        )
                    }
                }
            }
        }
    }

    private fun exitRoom() {
        // 停止本地服务器
        localNetworkService.stopServer()
        // 重置房间相关状态
        _uiState.update {
            it.copy(
                currentRoom = null,
                roomLink = null,
                isWaitingForPlayer = false,
                matchedOpponent = null
            )
        }
    }

    // 替换原有的parseIpAndPortFromRoomId函数
    private fun parseIpAndPortFromRoomId(roomLink: String): Triple<String?, Int?, String?> {
    return try {
        // 直接按 ":" 拆分字符串为三部分：IP:端口:房间号
        val parts = roomLink.split(":", limit = 3)
        if (parts.size != 3) {
            return Triple(null, null, null)
        }
        val ip = parts[0].takeIf { it.isNotBlank() }
        val port = parts[1].toIntOrNull()
        val roomId = parts[2].takeIf { it.isNotBlank() }
        Triple(ip, port, roomId)
    } catch (e: Exception) {
        Triple(null, null, null)
    }
}

    // 处理分享链接
    private fun shareRoomLink() {
        val link = _uiState.value.roomLink ?: return
        // 触发系统分享功能
        viewModelScope.launch(Dispatchers.Main) {
            val context = getApplication<Application>().applicationContext
            val shareIntent = Intent().apply {
                action = Intent.ACTION_SEND
                putExtra(Intent.EXTRA_TEXT, "$link")
                type = "text/plain"
            }
            context.startActivity(
                Intent.createChooser(shareIntent, "分享房间链接")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    override fun onCleared() {
        super.onCleared()
        viewModelScope.cancel() // 取消所有未完成的协程
    }

    private fun showErrorToast(message: String) {
        viewModelScope.launch(Dispatchers.Main) {
            val context = getApplication<Application>().applicationContext
            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
        }
    }

}
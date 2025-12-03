package com.example.onlinetictactoe

import android.app.Application
import android.content.Context
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
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
                val gameUpdate = GameUpdate(
                    roomId = roomId,
                    row = row,
                    col = col,
                    player = _uiState.value.currentPlayer, // 落子的玩家
                    gameResult = _uiState.value.gameResult
                )
                // 发送到对方IP（从房间信息中获取）
                val remoteIp = parseIpAndPortFromRoomId(_uiState.value.roomLink ?: "")?.first ?: return@launch
                localNetworkService.sendGameUpdate(remoteIp, 8080, gameUpdate)
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
        _uiState.update { state ->
            state.copy(
                board = List(state.boardSize) { List(state.boardSize) { CellState.EMPTY } },
                currentPlayer = CellState.X,
                gameResult = GameResult.PLAYING,
                errorMsg = null
            )
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
                kotlinx.coroutines.delay(2000)
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
    private val localNetworkService = LocalNetworkService { gameUpdate ->
        // 接收对方的游戏更新
        handleRemoteGameUpdate(gameUpdate)
    }

    // 处理对方发送的游戏更新
    private fun handleRemoteGameUpdate(update: GameUpdate) {
        // 验证房间ID是否匹配
        if (update.roomId != _uiState.value.currentRoom?.roomId) return

        viewModelScope.launch(Dispatchers.Main) {
            // 更新本地棋盘（对方落子的位置）
            val newBoard = _uiState.value.board.mapIndexed { r, rows ->
                rows.mapIndexed { c, cell ->
                    if (r == update.row && c == update.col) update.player else cell
                }
            }

            // 更新状态（切换当前玩家，同步游戏结果）
            _uiState.update {
                it.copy(
                    board = newBoard,
                    currentPlayer = if (update.player == CellState.X) CellState.O else CellState.X,
                    gameResult = update.gameResult
                )
            }

            // 如果游戏结束，保存记录
            if (update.gameResult != GameResult.PLAYING) {
                saveGameRecordToMemory(update.gameResult)
            }
        }
    }

    private fun createRoom(playerName: String) {
        _uiState.update { it.copy(isLoading = true) }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                // 启动本地服务器（端口8080）
                val localIp = localNetworkService.startServer(8080)
                // 生成本地网络房间链接（包含IP和端口）
                val roomId = "local_${System.currentTimeMillis()}" // 本地房间ID（简化）
                val link = "http://$localIp:8080/join?roomId=$roomId"

                // 创建本地房间信息
                val localRoom = GameRoom(
                    roomId = roomId,
                    host = playerName,
                    guest = null
                )

                withContext(Dispatchers.Main) {
                    _uiState.update {
                        it.copy(
                            currentRoom = localRoom,
                            roomLink = link,
                            isLoading = false,
                            currentScreen = Screen.GAME,
                            // 本地玩家作为X（先手）
                            currentPlayer = CellState.X
                        )
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    _uiState.update {
                        it.copy(isLoading = false, errorMsg = "创建房间失败：${e.message}")
                    }
                }
            }
        }
    }

    // 处理加入房间
    private fun joinRoom(roomId: String) {
        // 从DeepLink或输入中解析对方IP和端口（示例：http://192.168.1.100:8080/join?roomId=xxx）
        val (remoteIp, port) = parseIpAndPortFromRoomId(roomId) ?: run {
            _uiState.update { it.copy(errorMsg = "无效的房间链接", isLoading = false) }
            return
        }

        _uiState.update { it.copy(isLoading = true) }
        viewModelScope.launch(Dispatchers.Main) {
            try {
                // 加入房间时，本地玩家作为O（后手）
                _uiState.update {
                    it.copy(
                        currentPlayer = CellState.O,
                        currentRoom = GameRoom(roomId = roomId, host = "对方玩家", guest = "本地玩家"),
                        isLoading = false,
                        currentScreen = Screen.GAME
                    )
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(isLoading = false, errorMsg = "加入房间失败：${e.message}")
                }
            }
        }
    }

    // 辅助方法：从房间链接中解析IP和端口
    private fun parseIpAndPortFromRoomId(roomLink: String): Pair<String, Int>? {
        return try {
            val url = roomLink.replace("http://", "")
            val ipAndPort = url.split("/")[0]
            val parts = ipAndPort.split(":")
            Pair(parts[0], parts[1].toInt())
        } catch (e: Exception) {
            null
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
                putExtra(Intent.EXTRA_TEXT, "来和我一起玩井字棋吧！链接：$link")
                type = "text/plain"
            }
            context.startActivity(
                Intent.createChooser(shareIntent, "分享房间链接")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

}
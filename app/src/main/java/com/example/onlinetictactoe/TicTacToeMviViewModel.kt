package com.example.onlinetictactoe

import android.app.Application
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
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

    // 更新网络服务实例
    private val socketNetworkService = SocketNetworkService(
        onGameUpdate = { gameUpdate ->
            handleRemoteGameUpdate(gameUpdate)
        },
        getCurrentRoomId = {
            _uiState.value.currentRoom?.roomId
        }
    )

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

        // 切换下一个玩家
        val nextPlayer = if (_uiState.value.currentPlayer == CellState.X) CellState.O else CellState.X

        val updatedState = _uiState.value.copy(
            board = newBoard,
            currentPlayer = nextPlayer,
            myTurn = false
        )
        _uiState.update { updatedState }

        // 检查胜负
        checkWinner()

        // 如果是人机对战模式，且游戏仍在进行中，触发AI落子
        if (_uiState.value.gameMode == GameMode.HUMAN_VS_AI
            && _uiState.value.gameResult == GameResult.PLAYING) {
            aiMakeMove() // 调用AI落子逻辑
        }

        // 在线对战模式下，发送更新给对方
        if (_uiState.value.gameMode == GameMode.HUMAN_VS_HUMAN_ONLINE) {
            viewModelScope.launch(Dispatchers.IO) {
                val currentState = _uiState.value
                val roomId = currentState.currentRoom?.roomId ?: return@launch

                // 构建游戏更新 - 发送更新后的棋盘和下一个玩家
                val gameUpdate = GameUpdate(
                    roomId = roomId,
                    type = UpdateType.MOVE,
                    board = newBoard, // 发送最新棋盘
                    currentPlayer = nextPlayer, // 发送下一个玩家
                    gameResult = currentState.gameResult,
                    yourTurn = true // 对面的回合
                )

                // 发送更新
                socketNetworkService.sendGameUpdate(gameUpdate)
            }
        }
    }

    // AI落子逻辑（优先堵赢路、找赢路）
    private fun aiMakeMove() {
        _uiState.update { it.copy(myTurn = false) }
        val board = _uiState.value.board
        val boardSize = _uiState.value.boardSize
        val emptyCells = mutableListOf<Pair<Int, Int>>()

        // 第一步：找AI能一步赢的位置（O连3）
        val winningMove = findWinningMove(board, boardSize, CellState.O)
        if (winningMove != null) {
            executeAIMove(winningMove.first, winningMove.second)
            return
        }

        // 第二步：堵人类能一步赢的位置（X连3）
        val blockingMove = findWinningMove(board, boardSize, CellState.X)
        if (blockingMove != null) {
            executeAIMove(blockingMove.first, blockingMove.second)
            return
        }

        // 第三步：找空单元格（没有赢/堵的机会，随机选）
        for (row in 0 until boardSize) {
            for (col in 0 until boardSize) {
                if (board[row][col] == CellState.EMPTY) {
                    emptyCells.add(Pair(row, col))
                }
            }
        }

        if (emptyCells.isNotEmpty()) {
            val randomMove = emptyCells.random()
            executeAIMove(randomMove.first, randomMove.second)
        } else {
            _uiState.update { it.copy(myTurn = true) }
        }
    }

    // 辅助：找“一步赢”的位置（某方再落一子就能赢）
    private fun findWinningMove(
        board: List<List<CellState>>,
        boardSize: Int,
        player: CellState
    ): Pair<Int, Int>? {
        // 遍历所有空单元格，模拟落子后检查是否赢
        for (row in 0 until boardSize) {
            for (col in 0 until boardSize) {
                if (board[row][col] == CellState.EMPTY) {
                    val tempBoard = board.mapIndexed { r, rows ->
                        rows.mapIndexed { c, cell ->
                            if (r == row && c == col) player else cell
                        }
                    }
                    if (isPlayerWin(tempBoard, boardSize, player)) {
                        return Pair(row, col)
                    }
                }
            }
        }
        return null
    }

    // 辅助：检查某方是否赢了
    private fun isPlayerWin(
        board: List<List<CellState>>,
        boardSize: Int,
        player: CellState
    ): Boolean {
        // 检查行
        for (row in 0 until boardSize) {
            if (board[row].all { it == player }) return true
        }
        // 检查列
        for (col in 0 until boardSize) {
            var allSame = true
            for (row in 0 until boardSize) {
                if (board[row][col] != player) {
                    allSame = false
                    break
                }
            }
            if (allSame) return true
        }
        // 检查对角线（左上->右下）
        var diagonalWin = true
        for (i in 0 until boardSize) {
            if (board[i][i] != player) {
                diagonalWin = false
                break
            }
        }
        if (diagonalWin) return true
        // 检查对角线（右上->左下）
        diagonalWin = true
        for (i in 0 until boardSize) {
            if (board[i][boardSize - 1 - i] != player) {
                diagonalWin = false
                break
            }
        }
        return diagonalWin
    }

    // 辅助：执行AI落子
    private fun executeAIMove(row: Int, col: Int) {
        viewModelScope.launch(Dispatchers.Main) {
            delay(800) // 模拟思考延迟
            handleAICellClick(row, col)
            _uiState.update { it.copy(myTurn = true) }
        }
    }

    // AI专用的落子方法
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

    // 重置游戏
    private fun resetGame() {
        // 重置棋盘
        val newBoard = List(_uiState.value.boardSize) { List(_uiState.value.boardSize) { CellState.EMPTY } }

        val updatedState = _uiState.value.copy(
            board = newBoard,
            currentPlayer = CellState.X,
            gameResult = GameResult.PLAYING,
            errorMsg = null,
            isLoading = false,
            myTurn = true
        )
        _uiState.update { updatedState }

        // 在线模式下，发送重置更新给对方
        if (_uiState.value.gameMode == GameMode.HUMAN_VS_HUMAN_ONLINE) {
            viewModelScope.launch(Dispatchers.IO) {
                val roomId = _uiState.value.currentRoom?.roomId ?: return@launch

                val resetUpdate = GameUpdate(
                    roomId = roomId,
                    type = UpdateType.RESET,
                    board = updatedState.board,
                    currentPlayer = CellState.O,
                    gameResult = GameResult.PLAYING,
                    yourTurn = false
                )

                socketNetworkService.sendGameUpdate(resetUpdate)
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
            // 定义默认值
            val defaultMode = GameMode.HUMAN_VS_AI
            val defaultBoardSize = 3 // 默认棋盘大小

            try {
                if (configFile.exists()) {
                    // 文件存在：解析配置
                    val configJson = configFile.readText()
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
                    // 文件不存在时设默认值
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

    // 处理对方发送的游戏更新
    private fun handleRemoteGameUpdate(update: GameUpdate) {
        // 验证房间ID是否匹配
        if (update.roomId != _uiState.value.currentRoom?.roomId) return

        viewModelScope.launch(Dispatchers.Main) {
            when (update.type) {
                UpdateType.JOIN -> {
                    // 房主收到玩家加入通知
                    _uiState.update {
                        it.copy(
                            isWaitingForPlayer = false,
                            currentRoom = it.currentRoom?.copy(
                                isFull = true
                            )
                        )
                    }
                }

                UpdateType.MOVE -> {
                    // 同步棋盘状态
                    _uiState.update {
                        it.copy(
                            board = update.board,
                            currentPlayer = update.currentPlayer,  // 使用对方发送的下一个玩家
                            gameResult = update.gameResult,
                            myTurn = update.yourTurn
                        )
                    }

                    // 游戏结束处理
                    if (update.gameResult != GameResult.PLAYING) {
                        saveGameRecordToMemory(update.gameResult)
                    }
                }

                UpdateType.RESET -> {
                    // 同步重置游戏
                    _uiState.update {
                        it.copy(
                            board = update.board,
                            currentPlayer = update.currentPlayer,  // 同步当前玩家
                            gameResult = GameResult.PLAYING,
                            myTurn = update.yourTurn
                        )
                    }
                }

                UpdateType.DISCONNECT -> {
                    // 处理断开连接
                    _uiState.update {
                        it.copy(
                            errorMsg = "对方已断开连接",
                            currentRoom = null,
                            roomLink = null,
                            isWaitingForPlayer = false,
                        )
                    }
                    socketNetworkService.disconnect()
                }
            }
        }
    }

    private fun createRoom(playerName: String) {
        _uiState.update { it.copy(isLoading = true) }

        viewModelScope.launch(Dispatchers.IO) {
            try {
                // 启动Socket服务器
                val port = socketNetworkService.startServer { assignedPort ->
                    Log.d("TicTacToe", "服务器启动在端口: $assignedPort")
                }

                val localIp = socketNetworkService.getLocalIpAddress()
                val roomId = "room_${System.currentTimeMillis()}"
                val roomLink = "$localIp:$port:$roomId"

                val localRoom = GameRoom(
                    roomId = roomId,
                    host = playerName,
                    guest = null,
                    isFull = false
                )

                // 在 IO 线程中获取当前 boardSize
                val currentBoardSize = _uiState.value.boardSize

                withContext(Dispatchers.Main) {
                    _uiState.update { state ->
                        state.copy(
                            currentRoom = localRoom,
                            roomLink = roomLink,
                            isLoading = false,
                            currentScreen = Screen.GAME,
                            currentPlayer = CellState.X, // 房主永远是 X（先手）
                            gameMode = GameMode.HUMAN_VS_HUMAN_ONLINE,
                            isWaitingForPlayer = true,
                            gameResult = GameResult.PLAYING,
                            myTurn = true,
                            board = List(currentBoardSize) { List(currentBoardSize) { CellState.EMPTY } },
                        )
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            errorMsg = "创建房间失败: ${e.message}"
                        )
                    }
                }
            }
        }
    }

    // 处理加入房间
    private fun joinRoom(roomLink: String) {
        val (remoteIp, port, roomId) = parseIpAndPortFromRoomId(roomLink) ?: run {
            _uiState.update { it.copy(errorMsg = "无效的房间链接格式", isLoading = false, isHost = false) }
            return
        }

        if (remoteIp.isNullOrBlank() || port == null || roomId.isNullOrBlank()) {
            _uiState.update { it.copy(errorMsg = "链接缺少IP、端口或房间号", isLoading = false, isHost = false) }
            return
        }

        _uiState.update { it.copy(isLoading = true) }

        viewModelScope.launch(Dispatchers.IO) {
            try {
                // 检查房间是否存在
                val roomExists = socketNetworkService.checkRoomExists(remoteIp, port, roomId)

                if (!roomExists) {
                    withContext(Dispatchers.Main) {
                        _uiState.update {
                            it.copy(
                                isLoading = false,
                                errorMsg = "房间不存在或已关闭",
                                currentScreen = Screen.HOME,
                            )
                        }
                    }
                    return@launch
                }

                // 连接到服务器
                val connected = socketNetworkService.connectToServer(remoteIp, port)

                if (!connected) {
                    withContext(Dispatchers.Main) {
                        _uiState.update {
                            it.copy(
                                isLoading = false,
                                errorMsg = "无法连接到房间",
                                currentScreen = Screen.HOME
                            )
                        }
                    }
                    return@launch
                }

                // 发送加入通知
                val joinUpdate = GameUpdate(
                    roomId = roomId,
                    type = UpdateType.JOIN,
                    board = List(_uiState.value.boardSize) { List(_uiState.value.boardSize) { CellState.EMPTY } },
                    currentPlayer = CellState.X, // 房主是 X
                    gameResult = GameResult.PLAYING,
                    isHost = true,
                    yourTurn = true
                )

                socketNetworkService.sendGameUpdate(joinUpdate)

                withContext(Dispatchers.Main) {
                    _uiState.update { state ->
                        state.copy(
                            currentPlayer = CellState.O, // 客人是 O
                            currentRoom = GameRoom(
                                roomId = roomId,
                                host = "对方",
                                guest = "自己",
                                isFull = true
                            ),
                            isLoading = false,
                            currentScreen = Screen.GAME,
                            isWaitingForPlayer = false,
                            gameMode = GameMode.HUMAN_VS_HUMAN_ONLINE,
                            board = List(state.boardSize) { List(state.boardSize) { CellState.EMPTY } },
                            gameResult = GameResult.PLAYING,
                            myTurn = false,
                            isHost = false
                        )
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            errorMsg = "加入房间失败: ${e.message}",
                            currentScreen = Screen.HOME
                        )
                    }
                }
            }
        }
    }

    private fun exitRoom() {
        viewModelScope.launch(Dispatchers.IO) {
            val roomId = _uiState.value.currentRoom?.roomId
            if (roomId != null) {
                val disconnectUpdate = GameUpdate(
                    roomId = roomId,
                    type = UpdateType.DISCONNECT,
                    board = _uiState.value.board,
                    currentPlayer = _uiState.value.currentPlayer,
                    gameResult = _uiState.value.gameResult,
                    yourTurn = false
                )
                socketNetworkService.sendGameUpdate(disconnectUpdate)
            }

            socketNetworkService.disconnect()

            withContext(Dispatchers.Main) {
                _uiState.update {
                    it.copy(
                        currentRoom = null,
                        roomLink = null,
                        isWaitingForPlayer = false,
                    )
                }
            }
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
}
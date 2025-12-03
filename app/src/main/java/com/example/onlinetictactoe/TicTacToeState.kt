package com.example.onlinetictactoe

// MVI核心：单一状态源（移除数据库实体，记录改为内存字符串列表）
data class TicTacToeState(
    // 页面导航状态
    val currentScreen: Screen = Screen.HOME,
    // 游戏状态
    val gameMode: GameMode = GameMode.HUMAN_VS_AI,
    val board: List<List<CellState>> = List(3) { List(3) { CellState.EMPTY } },
    val currentPlayer: CellState = CellState.X,
    val gameResult: GameResult = GameResult.PLAYING,
    val isLoading: Boolean = false, // 加载状态（匹配/网络请求）
    val errorMsg: String? = null, // 错误信息
    // 在线匹配状态
    val isMatching: Boolean = false,
    val matchedOpponent: String? = null, // 匹配到的对手
    // 游戏记录（内存版：格式"时间 | 模式 | 结果"）
    val gameRecords: List<String> = emptyList(),
    // 配置信息
    val boardSize: Int = 3, // 棋盘大小（默认3x3）
    val savedMode: GameMode = GameMode.HUMAN_VS_AI, // 保存的默认模式

    val currentRoom: GameRoom? = null, // 当前房间信息
    val roomLink: String? = null, // 房间分享链接
    val isWaitingForPlayer: Boolean = true, // 新增：是否在等待玩家加入
)

// 页面枚举
enum class Screen {
    HOME, // 首页
    GAME, // 对战页
    RECORD, // 记录页（内存版）
    SETTINGS // 设置页
}
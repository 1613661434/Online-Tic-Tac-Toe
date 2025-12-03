package com.example.onlinetictactoe

// MVI核心：封装所有用户操作（移除数据库相关Intent）
sealed class TicTacToeIntent {
    // 游戏操作
    data class ClickCell(val row: Int, val col: Int) : TicTacToeIntent() // 点击棋盘格子
    object ResetGame : TicTacToeIntent() // 重置游戏
    object CheckWinner : TicTacToeIntent() // 检查胜负
    // 模式选择
    data class SelectGameMode(val mode: GameMode) : TicTacToeIntent() // 选择对战模式
    // 在线匹配
    object StartOnlineMatch : TicTacToeIntent() // 开始在线匹配
    object CancelOnlineMatch : TicTacToeIntent() // 取消在线匹配
    // 配置操作（保留文件处理）
    data class SaveConfig(val mode: GameMode, val boardSize: Int) : TicTacToeIntent() // 保存配置
    object LoadConfig : TicTacToeIntent() // 加载配置
    // 记录操作（内存版）
    object LoadGameRecords : TicTacToeIntent() // 加载内存记录
    object ClearGameRecords : TicTacToeIntent() // 清空内存记录

    data class CreateRoom(val playerName: String) : TicTacToeIntent() // 创建房间
    data class JoinRoom(val roomId: String) : TicTacToeIntent() // 加入房间
    object ShareRoomLink : TicTacToeIntent() // 分享房间链接
}

// 对战模式枚举
enum class GameMode {
    HUMAN_VS_AI, // 人机对战
    HUMAN_VS_HUMAN_ONLINE // 在线双人对战
}

// 棋盘格子状态
enum class CellState {
    EMPTY, X, O
}

// 游戏结果
enum class GameResult {
    WIN_X, WIN_O, DRAW, PLAYING
}
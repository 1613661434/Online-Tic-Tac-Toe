package com.example.onlinetictactoe

data class GameRoom(
    val roomId: String,
    val host: String,
    val guest: String?,
    val isFull: Boolean = false,
    val createdAt: Long = System.currentTimeMillis()
)

// 游戏更新数据类
data class GameUpdate(
    val roomId: String,
    val type: UpdateType, // 更新类型
    val board: List<List<CellState>>,
    val currentPlayer: CellState,
    val gameResult: GameResult,
    val yourTurn: Boolean,
    val isHost: Boolean = false // 是否是房主
)

// 更新类型枚举
enum class UpdateType {
    JOIN,      // 玩家加入
    MOVE,      // 落子
    RESET,     // 重置游戏
    DISCONNECT // 断开连接
}
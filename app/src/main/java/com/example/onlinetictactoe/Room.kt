package com.example.onlinetictactoe

data class GameRoom(
    val roomId: String,
    val host: String,
    val guest: String?,
    val isFull: Boolean = false,
    val createdAt: Long = System.currentTimeMillis()
)
package com.example.onlinetictactoe

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel

// 首页
@Composable
fun HomeScreen(viewModel: TicTacToeMviViewModel = viewModel()) {
    // 在HomeScreen函数内添加
    val connectionInfo = remember { mutableStateOf("") }

    // 解析「IP:端口:房间号」格式
    fun parseConnectionInfo(input: String): Triple<String?, Int?, String?> {
        val parts = input.split(":")
        if (parts.size != 3) return Triple(null, null, null)
        return Triple(
            parts[0].takeIf { it.isNotBlank() },  // first: IP
            parts[1].toIntOrNull(),               // second: 端口
            parts[2].takeIf { it.isNotBlank() }   // third: 房间号
        )
    }

    // 获取本地网络服务实例
    val localNetworkService = remember {
        LocalNetworkService(
            onGameUpdate = {},  // 提供onGameUpdate参数的空实现
            getCurrentRoomId = { null }  // 提供getCurrentRoomId参数的默认实现
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "在线井字棋",
            fontSize = 32.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )
        Spacer(modifier = Modifier.height(32.dp))

        // 人机对战
        Button(
            onClick = {
                viewModel.handleIntent(TicTacToeIntent.SelectGameMode(GameMode.HUMAN_VS_AI))
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("人机对战")
        }
        Spacer(modifier = Modifier.height(16.dp))

        // 在线双人对战
        val uiState = viewModel.uiState.collectAsStateWithLifecycle().value
        if (uiState.isMatching) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator()
                Spacer(modifier = Modifier.height(8.dp))
                Text("正在匹配对手...")
                OutlinedButton(onClick = {
                    viewModel.handleIntent(TicTacToeIntent.CancelOnlineMatch)
                }) {
                    Text("取消匹配")
                }
            }
        } else {
            Button(
                onClick = {
                    // 实际应用中应该让用户输入昵称
                    viewModel.handleIntent(TicTacToeIntent.CreateRoom("Host"))
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("创建房间")
            }
        }

        // 错误提示
        uiState.errorMsg?.let {
            Text(
                text = it,
                color = Color.Red,
                modifier = Modifier.padding(top = 16.dp)
            )
        }
        Spacer(modifier = Modifier.height(16.dp))
        Text("或输入连接信息（格式：IP:端口:房间号）：")
        TextField(
            value = connectionInfo.value,
            onValueChange = { connectionInfo.value = it },
            label = { Text("例如：192.168.1.100:8080:local_123456") },  // 示例改为新格式
            modifier = Modifier.fillMaxWidth()
        )
        Button(
            onClick = {
                // 解析输入的连接信息并连接
                val connectionTriple = parseConnectionInfo(connectionInfo.value)
                val ip = connectionTriple.first
                val port = connectionTriple.second
                val roomId = connectionTriple.third

                if (ip != null && port != null && roomId != null) {
                    // 直接使用IP:端口:房间号格式作为roomLink，不拼接URL
                    val roomLink = "$ip:$port:$roomId"
                    viewModel.handleIntent(TicTacToeIntent.JoinRoom(roomLink))
                } else {
                    viewModel.handleIntent(TicTacToeIntent.ShowError("格式错误，请使用 IP:端口:房间号"))
                }
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("连接对方")
        }
    }
}

// 对战页
@Composable
fun GameScreen(viewModel: TicTacToeMviViewModel = viewModel()) {
    val uiState = viewModel.uiState.collectAsStateWithLifecycle().value
    val boardSize = uiState.boardSize

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        // 对手信息
        uiState.matchedOpponent?.let {
            Text(
                text = "对手：$it",
                fontSize = 18.sp,
                modifier = Modifier.padding(bottom = 16.dp)
            )
        }

        // 游戏状态
// 游戏状态（修复后）
        Text(
            text = when {
                // 优先判断：在线模式且等待玩家时，显示等待提示
                uiState.isWaitingForPlayer && uiState.gameMode == GameMode.HUMAN_VS_HUMAN_ONLINE ->
                    "等待其他玩家加入..."

                // 游戏进行中
                uiState.gameResult == GameResult.PLAYING -> {
                    val playerName = when (uiState.gameMode) {
                        GameMode.HUMAN_VS_AI -> {
                            if (uiState.currentPlayer == CellState.X) "你" else "AI"
                        }
                        GameMode.HUMAN_VS_HUMAN_ONLINE -> {
                            if (uiState.currentPlayer == CellState.X) {
                                if (uiState.currentRoom?.host == "Host") "你" else "对方"
                            } else {
                                if (uiState.currentRoom?.guest == "本地玩家") "你" else "对方"
                            }
                        }
                    }
                    "当前回合：$playerName（${uiState.currentPlayer.name}）"
                }

                // X获胜
                uiState.gameResult == GameResult.WIN_X -> when (uiState.gameMode) {
                    GameMode.HUMAN_VS_AI -> "你赢了！"
                    GameMode.HUMAN_VS_HUMAN_ONLINE -> if (uiState.currentRoom?.host == "Host") "你赢了！" else "对方赢了！"
                }

                // O获胜
                uiState.gameResult == GameResult.WIN_O -> when (uiState.gameMode) {
                    GameMode.HUMAN_VS_AI -> "AI赢了！"
                    GameMode.HUMAN_VS_HUMAN_ONLINE -> if (uiState.currentRoom?.guest == "本地玩家") "你赢了！" else "对方赢了！"
                }

                // 平局
                uiState.gameResult == GameResult.DRAW -> "平局！"

                // 兜底分支（防止枚举新增值导致报错）
                else -> "游戏结束"
            },
            fontSize = 20.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(bottom = 16.dp)
        )

        // 在GameScreen.kt的Column中添加
        if (uiState.isWaitingForPlayer && uiState.gameMode == GameMode.HUMAN_VS_HUMAN_ONLINE) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                CircularProgressIndicator()
                Spacer(modifier = Modifier.height(8.dp))
                uiState.roomLink?.let { link ->
                    Text("房间链接: $link", fontSize = 12.sp)
                }
            }
        } else {
        // 棋盘
        Column {
            for (row in 0 until boardSize) {
                Row {
                    for (col in 0 until boardSize) {
                        val cell = uiState.board[row][col]
// 对战页中的棋盘格子部分修改
                        Card(
                            modifier = Modifier
                                .size(80.dp)
                                .padding(4.dp),
                            border = BorderStroke(1.dp, Color.Gray),
                            onClick = {
                                val canClick = when (uiState.gameMode) {
                                    GameMode.HUMAN_VS_AI -> {
                                        // 人机模式逻辑保持不变
                                        uiState.currentPlayer == CellState.X && !uiState.isLoading && uiState.gameResult == GameResult.PLAYING
                                    }
                                    GameMode.HUMAN_VS_HUMAN_ONLINE -> {
                                        !uiState.isWaitingForPlayer &&
                                                uiState.currentRoom?.isFull == true &&
                                                uiState.gameResult == GameResult.PLAYING &&
                                                !uiState.isLoading &&
                                                // 核心：当前玩家是否匹配（房主O/访客X）
                                                (uiState.currentPlayer == CellState.O && uiState.currentRoom?.host == "Host") ||  // 房主只能在O回合操作
                                                (uiState.currentPlayer == CellState.X && uiState.currentRoom?.guest == "本地玩家")  // 访客只能在X回合操作
                                    }
                                }

                                if (canClick) {
                                    viewModel.handleIntent(TicTacToeIntent.ClickCell(row, col))
                                }
                            }
                        ) {
                            // 现有内容保持不变
                            Box(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = cell.name,
                                    fontSize = 32.sp,
                                    color = when (cell) {
                                        CellState.X -> Color.Red
                                        CellState.O -> Color.Blue
                                        CellState.EMPTY -> Color.Transparent
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // 重置按钮
        Button(
            onClick = { viewModel.handleIntent(TicTacToeIntent.ResetGame) },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("重置游戏")
        }
        if (uiState.roomLink != null) {
            Button(
                onClick = { viewModel.handleIntent(TicTacToeIntent.ShareRoomLink) },
                modifier = Modifier.padding(top = 16.dp)
            ) {
                Text("分享房间链接")
            }
        }
    }
}

// 记录页（内存版）
@Composable
fun RecordScreen(viewModel: TicTacToeMviViewModel = viewModel()) {
    val uiState = viewModel.uiState.collectAsStateWithLifecycle().value

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Text("游戏记录（内存版）", fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(16.dp))

        if (uiState.gameRecords.isEmpty()) {
            Text("暂无游戏记录", modifier = Modifier.align(Alignment.CenterHorizontally))
        } else {
            LazyColumn {
                items(uiState.gameRecords.size) { index ->
                    val record = uiState.gameRecords[index]
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(8.dp),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary)
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(record)
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
        Button(
            onClick = { viewModel.handleIntent(TicTacToeIntent.ClearGameRecords) },
            modifier = Modifier.align(Alignment.CenterHorizontally)
        ) {
            Text("清空记录")
        }
    }
}

// 设置页（保留文件处理）
@Composable
fun SettingsScreen(viewModel: TicTacToeMviViewModel = viewModel()) {
    val uiState = viewModel.uiState.collectAsStateWithLifecycle().value

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("游戏设置", fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(32.dp))

        // 默认模式
        Text("当前默认模式：${uiState.savedMode.name}", fontSize = 18.sp)
        Spacer(modifier = Modifier.height(16.dp))

        // 选择默认模式
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            Button(
                onClick = {
                    viewModel.handleIntent(
                        TicTacToeIntent.SaveConfig(GameMode.HUMAN_VS_AI, uiState.boardSize)
                    )
                }
            ) {
                Text("默认人机对战")
            }
            Button(
                onClick = {
                    viewModel.handleIntent(
                        TicTacToeIntent.SaveConfig(GameMode.HUMAN_VS_HUMAN_ONLINE, uiState.boardSize)
                    )
                }
            ) {
                Text("默认在线对战")
            }
        }

        Spacer(modifier = Modifier.height(32.dp))

        // 棋盘大小（简化为3x3固定，可扩展）
        Text("棋盘大小：${uiState.boardSize}x${uiState.boardSize}", fontSize = 18.sp)
        Spacer(modifier = Modifier.height(16.dp))

        // 加载配置
        Button(onClick = { viewModel.handleIntent(TicTacToeIntent.LoadConfig) }) {
            Text("加载本地配置")
        }
    }
}

// 导航宿主
@Composable
fun AppNavHost(viewModel: TicTacToeMviViewModel = viewModel()) {
    val uiState = viewModel.uiState.collectAsStateWithLifecycle().value

    MainScaffold(viewModel = viewModel) {
        when (uiState.currentScreen) {
            Screen.HOME -> HomeScreen(viewModel)
            Screen.GAME -> GameScreen(viewModel)
            Screen.RECORD -> RecordScreen(viewModel)
            Screen.SETTINGS -> SettingsScreen(viewModel)
        }
    }
}
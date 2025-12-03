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
        // 手动输入IP连接
        Spacer(modifier = Modifier.height(16.dp))
        Text("或输入对方IP连接：")
        val ipText = remember { mutableStateOf("") }
        TextField(
            value = ipText.value,
            onValueChange = { ipText.value = it },
            label = { Text("对方IP地址（如192.168.1.100）") },
            modifier = Modifier.fillMaxWidth()
        )
        Button(
            onClick = {
                val roomLink = "http://${ipText.value}:8080/join?roomId=local_manual"
                viewModel.handleIntent(TicTacToeIntent.JoinRoom(roomLink))
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
        Text(
            text = when (uiState.gameResult) {
                GameResult.PLAYING -> "当前玩家：${uiState.currentPlayer.name}"
                GameResult.WIN_X -> "X 获胜！"
                GameResult.WIN_O -> "O 获胜！"
                GameResult.DRAW -> "平局！"
            },
            fontSize = 20.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(bottom = 16.dp)
        )

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
                                // 新增判断条件：不是AI回合且游戏进行中才允许点击
                                val canClick = when (uiState.gameMode) {
                                    GameMode.HUMAN_VS_AI -> {
                                        // 人机模式下，只有当前玩家是X（假设X是人类）且不在加载中才能点击
                                        uiState.currentPlayer == CellState.X && !uiState.isLoading && uiState.gameResult == GameResult.PLAYING
                                    }
                                    GameMode.HUMAN_VS_HUMAN_ONLINE -> {
                                        // 在线模式保持原有逻辑
                                        uiState.gameResult == GameResult.PLAYING && !uiState.isLoading
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
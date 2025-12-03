package com.example.onlinetictactoe

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope // 新增导入
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch // 新增导入

class MainActivity : ComponentActivity() {
    // 声明ViewModel成员变量
    private lateinit var ticTacToeViewModel: TicTacToeMviViewModel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 初始化ViewModel（在Activity中正确获取ViewModel）
        ticTacToeViewModel = ViewModelProvider(
            this,
            ViewModelProvider.AndroidViewModelFactory.getInstance(application)
        )[TicTacToeMviViewModel::class.java]

        handleDeepLink(intent)

        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    // 使用已初始化的ViewModel
                    ticTacToeViewModel.handleIntent(TicTacToeIntent.LoadConfig)
                    AppNavHost(viewModel = ticTacToeViewModel)
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        intent?.let { handleDeepLink(it) }
    }

    private fun handleDeepLink(intent: Intent) {
        val data = intent.data
        // 修正拼写错误（tictactoe -> 正确拼写，通常保持小写但确保拼写正确）
        if (data != null && "https".equals(data.scheme, ignoreCase = true) &&
            "tictactoe.example.com".equals(data.host, ignoreCase = true) &&
            data.path?.startsWith("/join") == true
        ) {
            val roomId = data.getQueryParameter("roomId")
            roomId?.let {
                // 使用Activity的lifecycleScope而非viewModelScope
                lifecycleScope.launch {
                    delay(500) // 延迟确保ViewModel初始化完成
                    // 调用ViewModel的方法（使用成员变量）
                    ticTacToeViewModel.handleIntent(TicTacToeIntent.JoinRoom(roomId))
                }
            }
        }
    }
}
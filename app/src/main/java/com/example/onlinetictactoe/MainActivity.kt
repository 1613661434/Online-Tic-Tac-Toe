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
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private lateinit var ticTacToeViewModel: TicTacToeMviViewModel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        ticTacToeViewModel = ViewModelProvider(
            this,
            ViewModelProvider.AndroidViewModelFactory.getInstance(application)
        )[TicTacToeMviViewModel::class.java]

        // 处理深度链接
        handleDeepLink(intent)

        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
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
        val action = intent.action

        when {
            // 处理标准HTTP深度链接
            data != null && "http".equals(data.scheme, ignoreCase = true) &&
                    data.path?.startsWith("/join") == true -> {
                // 尝试从查询参数获取房间链接
                val roomLink = data.getQueryParameter("roomLink")
                roomLink?.let { handleRoomLink(it) }
            }

            // 处理文本分享（直接复制 IP:端口:房间号）
            intent.action == Intent.ACTION_SEND -> {
                val sharedText = intent.getStringExtra(Intent.EXTRA_TEXT)
                sharedText?.let {
                    // 检查是否是房间链接格式 (IP:端口:房间号)
                    if (it.matches(Regex("""\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3}:\d+:.+"""))) {
                        handleRoomLink(it)
                    }
                }
            }

            // 处理直接通过系统浏览器或链接打开的IP地址
            data != null && data.toString().contains(":") -> {
                // 直接使用整个链接
                val roomLink = data.toString()
                    .replace("http://", "")
                    .replace("https://", "")
                if (roomLink.matches(Regex("""\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3}:\d+:.+"""))) {
                    handleRoomLink(roomLink)
                }
            }
        }
    }

    private fun handleRoomLink(roomLink: String) {
        lifecycleScope.launch {
            delay(500) // 确保ViewModel初始化完成
            ticTacToeViewModel.handleIntent(TicTacToeIntent.JoinRoom(roomLink))
        }
    }
}
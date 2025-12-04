package com.example.onlinetictactoe

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List // 替换过时图标
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.flow.update

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScaffold(
    viewModel: TicTacToeMviViewModel = viewModel(),
    content: @Composable () -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Online Tic-Tac-Toe") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = Color.White,
                    actionIconContentColor = Color.White,
                    navigationIconContentColor = Color.White
                ),
                navigationIcon = {
                    IconButton(onClick = {
                        viewModel.handleIntent(TicTacToeIntent.ExitRoom)
                        viewModel._uiState.update { it.copy(currentScreen = Screen.HOME) }
                    }) {
                        Icon(Icons.Default.Home, contentDescription = "Home")
                    }
                },
                actions = {
                    IconButton(onClick = {
                        viewModel.handleIntent(TicTacToeIntent.ExitRoom)
                        viewModel.handleIntent(TicTacToeIntent.LoadGameRecords)
                        viewModel._uiState.update { it.copy(currentScreen = Screen.RECORD) }
                    }) {
                        Icon(Icons.AutoMirrored.Filled.List, contentDescription = "Records")
                    }
                    IconButton(onClick = {
                        viewModel._uiState.update { it.copy(currentScreen = Screen.SETTINGS) }
                    }) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings")
                    }
                }
            )
        }
    ) { innerPadding ->
        // 应用Scaffold的内容内边距
        Box(modifier = Modifier.padding(innerPadding)) {
            content()
        }
    }
}
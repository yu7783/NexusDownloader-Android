package com.nexus.downloader.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.nexus.downloader.NexusApp
import com.nexus.downloader.AppContainer

/**
 * 手動 DI コンテナから ViewModel を生成するための Compose ヘルパ。
 * 各画面で `nexusViewModel { DownloadsViewModel(it) }` のように使う。
 */
@Composable
inline fun <reified VM : ViewModel> nexusViewModel(
    crossinline create: (AppContainer) -> VM,
): VM {
    val app = LocalContext.current.applicationContext as NexusApp
    val factory = remember { viewModelFactory { initializer { create(app.container) } } }
    return viewModel(factory = factory)
}

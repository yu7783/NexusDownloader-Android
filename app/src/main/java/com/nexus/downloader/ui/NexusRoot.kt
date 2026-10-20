package com.nexus.downloader.ui

import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.nexus.downloader.data.share.ShareInbox
import com.nexus.downloader.ui.about.AboutScreen
import com.nexus.downloader.ui.addons.AddonsScreen
import com.nexus.downloader.ui.downloads.DownloadsScreen
import com.nexus.downloader.ui.navigation.NexusDestination
import com.nexus.downloader.ui.settings.SettingsScreen

/** アプリのルート。ボトムナビゲーションとアニメーション付きの画面遷移。 */
@Composable
fun NexusRoot() {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route ?: NexusDestination.DOWNLOADS.route

    // 他アプリから共有されたらダウンロード画面へ自動で切り替える。
    // 共有 URL 自体は DownloadsViewModel が consume するため、ここでは
    // 消費の影響を受けない shareEvent（通し番号）を監視する。
    val shareEvent by ShareInbox.shareEvent.collectAsStateWithLifecycle()
    LaunchedEffect(shareEvent) {
        if (shareEvent <= 0L) return@LaunchedEffect
        navController.navigate(NexusDestination.DOWNLOADS.route) {
            popUpTo(navController.graph.findStartDestination().id) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Scaffold(
            bottomBar = {
                NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                    NexusDestination.entries.forEach { destination ->
                        val selected = currentRoute == destination.route
                        NavigationBarItem(
                            selected = selected,
                            onClick = {
                                if (!selected) {
                                    navController.navigate(destination.route) {
                                        popUpTo(navController.graph.findStartDestination().id) {
                                            saveState = true
                                        }
                                        launchSingleTop = true
                                        restoreState = true
                                    }
                                }
                            },
                            icon = {
                                Icon(
                                    destination.icon,
                                    contentDescription = stringResource(destination.labelRes),
                                )
                            },
                            label = { Text(stringResource(destination.labelRes)) },
                        )
                    }
                }
            },
        ) { innerPadding ->
            NavHost(
                navController = navController,
                startDestination = NexusDestination.DOWNLOADS.route,
                modifier = Modifier.padding(innerPadding),
                enterTransition = { slideInHorizontally(initialOffsetX = { it / 6 }, animationSpec = tween(300)) + fadeIn(tween(300)) },
                exitTransition = { fadeOut(tween(200)) },
                popEnterTransition = { fadeIn(tween(300)) },
                popExitTransition = { slideOutHorizontally(targetOffsetX = { it / 6 }, animationSpec = tween(300)) + fadeOut(tween(300)) },
            ) {
                composable(NexusDestination.DOWNLOADS.route) { DownloadsScreen() }
                composable(NexusDestination.ADDONS.route) { AddonsScreen() }
                composable(NexusDestination.SETTINGS.route) { SettingsScreen() }
                composable(NexusDestination.ABOUT.route) { AboutScreen() }
            }
        }
    }
}

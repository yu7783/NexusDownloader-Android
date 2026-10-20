package com.nexus.downloader.ui.navigation

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Extension
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.ui.graphics.vector.ImageVector
import com.nexus.downloader.R

/** ボトムナビゲーションの各目的地。 */
enum class NexusDestination(
    val route: String,
    @StringRes val labelRes: Int,
    val icon: ImageVector,
) {
    DOWNLOADS("downloads", R.string.nav_downloads, Icons.Rounded.Download),
    ADDONS("addons", R.string.nav_addons, Icons.Rounded.Extension),
    SETTINGS("settings", R.string.nav_settings, Icons.Rounded.Tune),
    ABOUT("about", R.string.nav_about, Icons.Rounded.Info),
}

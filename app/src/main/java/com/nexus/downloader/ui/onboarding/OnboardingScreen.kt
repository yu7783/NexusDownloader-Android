package com.nexus.downloader.ui.onboarding

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.nexus.downloader.R
import kotlinx.coroutines.launch

/** セットアップの 1 ページ。 */
private data class OnboardingPage(
    val icon: ImageVector,
    @StringRes val titleRes: Int,
    @StringRes val bodyRes: Int,
)

/** 初回起動時に表示するセットアップのページ一覧。 */
private val ONBOARDING_PAGES = listOf(
    OnboardingPage(
        icon = Icons.Rounded.Download,
        titleRes = R.string.onboarding_welcome_title,
        bodyRes = R.string.onboarding_welcome_body,
    ),
    OnboardingPage(
        icon = Icons.Rounded.Share,
        titleRes = R.string.onboarding_share_title,
        bodyRes = R.string.onboarding_share_body,
    ),
    OnboardingPage(
        icon = Icons.Rounded.Schedule,
        titleRes = R.string.onboarding_queue_title,
        bodyRes = R.string.onboarding_queue_body,
    ),
    OnboardingPage(
        icon = Icons.Rounded.FolderOpen,
        titleRes = R.string.onboarding_storage_title,
        bodyRes = R.string.onboarding_storage_body,
    ),
    OnboardingPage(
        icon = Icons.Rounded.Notifications,
        titleRes = R.string.onboarding_permission_title,
        bodyRes = R.string.onboarding_permission_body,
    ),
)

/**
 * 初回起動時のセットアップ画面。
 *
 * 横スワイプで進む複数ページのオンボーディング。ページが切り替わると
 * アイコンが拡大しながらフェードインするアニメーション付き。
 * 最後のページでは通知権限のリクエストができる。
 *
 * @param onFinished セットアップを完了（またはスキップ）したときに呼ばれる。
 */
@Composable
fun OnboardingScreen(onFinished: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val pagerState = rememberPagerState(pageCount = { ONBOARDING_PAGES.size })
    val lastIndex = ONBOARDING_PAGES.lastIndex

    var notificationsGranted by remember { mutableStateOf(hasNotificationPermission(context)) }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted -> notificationsGranted = granted }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // スキップは最後のページ以外で表示する。
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (pagerState.currentPage < lastIndex) {
                    TextButton(onClick = onFinished) {
                        Text(stringResource(R.string.onboarding_skip))
                    }
                }
            }

            HorizontalPager(
                state = pagerState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            ) { page ->
                OnboardingPageContent(
                    page = ONBOARDING_PAGES[page],
                    active = pagerState.currentPage == page,
                )
            }

            PageIndicator(count = ONBOARDING_PAGES.size, current = pagerState.currentPage)

            Spacer(Modifier.height(24.dp))

            if (pagerState.currentPage == lastIndex) {
                if (!notificationsGranted && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    OutlinedButton(
                        onClick = {
                            permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.onboarding_permission_button))
                    }
                    Spacer(Modifier.height(12.dp))
                }
                Button(
                    onClick = onFinished,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.onboarding_start))
                }
            } else {
                Button(
                    onClick = {
                        scope.launch {
                            pagerState.animateScrollToPage(pagerState.currentPage + 1)
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.onboarding_next))
                }
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}

/** ページ本文。アクティブなページだけアイコンを大きく表示する。 */
@Composable
private fun OnboardingPageContent(page: OnboardingPage, active: Boolean) {
    val iconScale by animateFloatAsState(
        targetValue = if (active) 1f else 0.6f,
        animationSpec = tween(400),
        label = "onboardingIconScale",
    )
    val iconAlpha by animateFloatAsState(
        targetValue = if (active) 1f else 0.35f,
        animationSpec = tween(400),
        label = "onboardingIconAlpha",
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.primaryContainer,
            modifier = Modifier
                .size(148.dp)
                .scale(iconScale)
                .alpha(iconAlpha),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = page.icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(64.dp),
                )
            }
        }

        Spacer(Modifier.height(36.dp))

        Text(
            text = stringResource(page.titleRes),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onBackground,
        )

        Spacer(Modifier.height(12.dp))

        Text(
            text = stringResource(page.bodyRes),
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** ページ位置を示すドット。現在ページは横長に伸びる。 */
@Composable
private fun PageIndicator(count: Int, current: Int) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(count) { index ->
            val selected = index == current
            val indicatorWidth by animateFloatAsState(
                targetValue = if (selected) 24f else 8f,
                animationSpec = tween(300),
                label = "onboardingIndicatorWidth",
            )
            Box(
                modifier = Modifier
                    .width(indicatorWidth.dp)
                    .height(8.dp)
                    .clip(CircleShape)
                    .background(
                        if (selected) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.outlineVariant
                        },
                    ),
            )
        }
    }
}

/** 通知権限が許可済みか（Android 13 未満は常に true）。 */
private fun hasNotificationPermission(context: Context): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
    return ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.POST_NOTIFICATIONS,
    ) == PackageManager.PERMISSION_GRANTED
}

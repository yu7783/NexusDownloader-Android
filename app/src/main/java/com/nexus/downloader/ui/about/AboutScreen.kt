package com.nexus.downloader.ui.about

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

private const val APP_NAME = "Nexus Downloader"
private const val APP_VERSION = "1.0.0"
private const val APP_AUTHOR = "VPTensor35"
private const val APP_LICENSE = "Apache License 2.0"
private const val APP_HOMEPAGE = "https://github.com/yu7783"

/** 情報画面。 */
@Composable
fun AboutScreen() {
    LazyColumn(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 40.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Text(
                text = "情報",
                style = MaterialTheme.typography.displaySmall,
                fontWeight = FontWeight.Bold,
            )
        }

        item {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                ),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(20.dp)) {
                    Text(APP_NAME, style = MaterialTheme.typography.headlineSmall)
                    Text(
                        "v$APP_VERSION",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "本体を汚さず拡張できる汎用ダウンローダーの Android 版。" +
                            "動画・画像・直リンクの並列ダウンロード、内蔵アドオン、" +
                            "ローカルの定義ファイルによる追加に対応します。",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }

        item {
            InfoCard(
                "主な機能",
                listOf(
                    "複数 URL の並列ダウンロード（Coroutines）",
                    "内蔵アドオン: yt-dlp / Iwara / 画像ギャラリー / 直リンク",
                    "ローカルファイルからのアドオン追加と危険コード走査",
                    "ダイナミックカラーとライト/ダークテーマ",
                    "yt-dlp + ffmpeg による多彩なサイト対応",
                ),
            )
        }

        item {
            InfoCard(
                "スマホ向けの便利機能",
                listOf(
                    "共有メニューから URL を送信（Morphe YT / ブラウザ / SNS など）",
                    "クリップボードの URL をワンタップで取り込み",
                    "ダウンロード完了の通知",
                    "Wi-Fi 接続時のみダウンロード",
                    "保存したファイルをその場で「開く / 共有」",
                ),
            )
        }

        item {
            InfoCard(
                "クレジット",
                listOf(
                    "作者: $APP_AUTHOR",
                    "ライセンス: $APP_LICENSE",
                    "ホームページ: $APP_HOMEPAGE",
                    "yt-dlp: github.com/yt-dlp/yt-dlp",
                    "youtubedl-android: github.com/yausername/youtubedl-android",
                ),
            )
        }

        item {
            InfoCard(
                "保存先",
                listOf(
                    "アプリ専用の Download フォルダ配下に保存されます。",
                    "設定の「保存先フォルダ名」で変更できます。",
                    "Android 11+ のスコープドストレージに準拠しています。",
                ),
            )
        }
    }
}

@Composable
private fun InfoCard(title: String, lines: List<String>) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            lines.forEach { line ->
                Text(
                    text = "・$line",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 2.dp),
                )
            }
        }
    }
}

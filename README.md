# Nexus Downloader — Android (Kotlin / Jetpack Compose)

Python 版 **[Nexus Downloader](https://github.com/yu7783/NexusDownloader)**（PyQt6 + PyQt-Fluent-Widgets）を
Android ネイティブへ移植し、UI を **Material 3 / Jetpack Compose** でモダンに再設計したものです。

## 技術スタック

| 項目 | 採用 |
|---|---|
| 言語 | Kotlin |
| UI | Jetpack Compose + Material 3 |
| 非同期 | Kotlin Coroutines / Flow |
| ビルド | Android Gradle Plugin 8.7 / Kotlin 2.0 |
| 対応 | minSdk 26 / compileSdk 36 / targetSdk 35 |
| 主要ライブラリ | OkHttp / Jsoup / kotlinx.serialization / DataStore / Navigation-Compose / youtubedl-android |

## モダン UI のポイント

- **Material 3 コンポーネント**: Card / NavigationBar / AssistChip / SegmentedButton / Slider など。
- **角丸 + 余白**: `NexusShapes`（12〜30dp の RoundedCorner）と 16〜20dp の余白でゆとりあるレイアウト。
- **ダイナミックカラー**: Android 12+ で OS の壁紙配色に追従（設定で ON/OFF 可）。
- **ライト / ダーク両対応**: ダークは視認性の高いダークグレー基調（`DarkBackground #121316` など）。
- **アニメーション**: 画面遷移にスライド + フェード、リストは `animateItem()`、
  進捗は `animateFloatAsState`、オプション表示は `AnimatedVisibility`。
- **縦画面フル活用**: `LazyColumn` によるスクロール可能なレイアウト。

## 📱 スマホ向け機能

PC 版にあったデスクトップ前提の項目（本体の自己更新 / 更新リポジトリ / HTTP プロキシ /
User-Agent / チャンクサイズ）は**スマホでは不要なため削除**し、代わりにモバイル向けの
機能を追加しています。

| 機能 | 説明 |
|---|---|
| **共有から受信** | 他アプリ（**Morphe YT** / ブラウザ / SNS など）の共有メニューや「リンクを開く」から `Nexus Downloader` を選ぶと、**元のアプリの上にインテント専用の設定ポップアップ**（`ShareOptionsActivity`）が開きます。`ACTION_SEND` / `ACTION_SEND_MULTIPLE` / `ACTION_VIEW` に対応 |
| **共有で自動開始** | ポップアップは設定した秒数（既定 **15 秒** / 1〜600 秒で変更可）後に自動でダウンロードを開始し、そのまま元のアプリへ戻ります（設定で OFF にすると手動開始） |
| **バックグラウンド実行** | 開始後は前面サービス（`DownloadService`）で動き続けるため、元のアプリへ戻っても（= アプリがバックグラウンドでも）ダウンロードが止まりません |
| **進行状況 / 残り時間の通知** | 実行中は進行状況と推定残り時間を継続通知に表示。Android 16+ では**リッチ オンゴーイング通知（ライブ更新）**として昇格し、ステータスバーのチップと通知シェードに進捗・残り時間をリアルタイム表示します |
| **保存先を選べる** | 設定 → ダウンロード → 保存先。**「フォルダを選ぶ」**で任意のフォルダ（SAF）を指定でき、完了したファイルはそこへ移動します。未選択時は**アプリ専用フォルダ**（サブフォルダ名・外部 / 内部ストレージを選択可）に保存 |
| **ダウンロードキュー** | 実行中でも URL を追加でき、順番に自動で処理します。待機中の並び順表示・**先頭へ移動**・1 件だけの中止に対応 |
| **一時停止 / 再開（レジューム）** | 画面または**通知のボタン**から一時停止・再開できます。直リンク / Iwara は `.part` + HTTP Range で**続きから**取得（対応サーバのみ） |
| **クリップボード提案** | リンクをコピーしていると、ダウンロード画面に「取り込む」カードが現れます |
| **完了通知** | ダウンロードが終わると通知を表示（Android 13+ は権限を初回に要求） |
| **Wi-Fi 限定** | モバイル通信でのダウンロードを防止するスイッチ |
| **開く / 共有** | 完了したカードから、保存したファイルをその場で開いたり共有したりできます（FileProvider 経由） |
| **統計** | 「これまでの実績」として累計件数・累計容量を表示（リセット可） |

> 共有 / 「リンクを開く」は **インテント専用の `ShareOptionsActivity`**（透明テーマ + 独自タスク）が受け取ります。
> 元のアプリの上にポップアップとして重なり、開始すると `DownloadManager` が前面サービスで
> ダウンロードを走らせ、アクティビティは `finish()` して**そのまま元のアプリへ戻ります**。
> アプリ本体（`MainActivity`）がインテントで直接起動された場合は、従来どおり
> `ShareIntentUrls` → `ShareInbox` → `DownloadsViewModel` の順に流れます。

## アーキテクチャ（Python → Kotlin 対応）

| Python | Android (Kotlin) |
|---|---|
| `main.py`（4 タブ GUI / エントリ） | `MainActivity` + `ui/` の画面（Downloads / Addons / Settings / About） |
| `core.Core`（司令塔・動的ロード・更新） | `data/addon/AddonRepository` + `data/config/ConfigRepository` |
| `security.SecurityGuard` | `data/security/SecurityGuard` |
| `addons/yt_dlp_addon.py` | `download/engine/YtDlpDownloader`（youtubedl-android） |
| `addons/iwara_addon.py` | `download/engine/IwaraDownloader`（Iwara 公式 API） |
| `addons/momonga_addon.py` | `download/engine/GalleryDownloader`（Jsoup） |
| 直リンク | `download/engine/DirectDownloader`（OkHttp） |
| `main.MultiDownloadWorker`（並列） | `download/DownloadManager`（Coroutine + Semaphore） |
| `config.json` | DataStore(Preferences) に JSON で保存 |

```
android/app/src/main/java/com/nexus/downloader/
 ├── NexusApp.kt / MainActivity.kt     # エントリ（yt-dlp 初期化・DI コンテナ）
 ├── data/
 │    ├── model/       # AppConfig / Addon / DownloadTask / OptionSpec
 │    ├── config/      # ConfigRepository（DataStore）
 │    ├── addon/       # AddonRepository / BuiltinAddons（内蔵 4 アドオン）
 │    ├── file/        # FileOpener / SaveLocation（保存先の解決・SAF への移動）
 │    ├── share/       # ShareInbox / ShareIntentUrls（共有 URL の受け取り）
 │    └── security/    # SecurityGuard（危険記述スキャン）
 ├── notification/
 │    └── NotificationHelper.kt        # 完了通知 + 進行状況（ライブ更新）通知
 ├── download/
 │    ├── DownloadManager.kt           # 並列スケジューラ
 │    ├── DownloadService.kt           # 前面サービス（バックグラウンド実行の維持）
 │    ├── DownloadSupport.kt           # 環境・HTTP クライアント・命名
 │    └── engine/      # Direct / Iwara / Gallery / YtDlp
 └── ui/
      ├── theme/       # Material3 テーマ（Color / Type / Theme）
      ├── navigation/  # 目的地定義
      ├── components/  # OptionField / TaskCard
      ├── share/       # ShareOptionsActivity（インテント専用ポップアップ）
      ├── downloads/   # ダウンロード画面 + VM
      ├── addons/      # アドオン管理画面 + VM
      ├── settings/    # 設定画面 + VM
      └── about/       # 情報画面
```

### 保存先の扱い（Android 10 以降）

Android 10 以降は共有ストレージ（例: 内部ストレージ/Download）へ**直接ファイルを書けません**。
そのため次の 2 通りを用意しています。

| モード | 保存場所 | 備考 |
|---|---|---|
| アプリ専用フォルダ（既定） | `Android/data/<pkg>/files/Download/<サブフォルダ>` | 常に書き込み可能。ファイルアプリからも参照可能。 |
| フォルダを選ぶ（SAF） | ユーザーが選んだ任意のフォルダ | ダウンロード中はアプリ専用フォルダで作業し、**完了後に選択フォルダへ移動**して元ファイルを削除します（`DocumentFile` ではなく `DocumentsContract` を使用）。 |

- 「フォルダを選ぶ」は `ACTION_OPEN_DOCUMENT_TREE`（SAF）で、`takePersistableUriPermission` により
  再起動後も書き込めます。Download 直下など一部のフォルダは OS が選択を禁止しているため、
  「新しいフォルダを作成」して選ぶか、既存の任意フォルダ（例: `NexusDL`）を選んでください。
- 「SD カード」などの別ボリュームのフォルダも選択できます（移動はコピー + 削除のため、
  一時的に空き容量を 1 ファイル分余分に使います）。

### 通知まわりの権限（AndroidManifest）

| 権限 | 用途 |
|---|---|
| `POST_NOTIFICATIONS` | 完了 / 進行状況の通知（Android 13+ は初回に実行時要求） |
| `FOREGROUND_SERVICE` / `FOREGROUND_SERVICE_DATA_SYNC` | バックグラウンドでダウンロードを続ける前面サービス |
| `POST_PROMOTED_NOTIFICATIONS` | リッチ オンゴーイング通知（Android 16+ のライブ更新）への昇格 |

## アドオンについて

- **既存アドオンは内蔵**（`BuiltinAddons`）:
  - `yt_dlp_core` … yt-dlp エンジン（YouTube / X / TikTok / ニコニコ など）
  - `iwara` … Iwara 動画
  - `manga_gallery` … 画像ギャラリー（momon-ga / ddd-smart など静的サイト）
  - `direct_link` … 直リンクのフォールバック
- **外部導入はローカルファイルからのみ**（ネットワーク経由の追加は行いません）。
  SAF（`OpenDocument`）で選択した JSON 定義を、危険キーワード走査（`SecurityGuard`）に
  通したうえで `files/addons/<id>.json` に保存します。

### アドオン定義 JSON の書式

```json
{
  "id": "my_site",
  "name": "My Site",
  "version": "1.0.0",
  "urlPatterns": ["example.com"],
  "type": "GALLERY",
  "description": "画像を一括取得",
  "gallery": {
    "imageSelector": "img.main",
    "imageAttr": "src",
    "titleSelector": "h1",
    "nextPageSelector": "a.next",
    "maxPages": 30,
    "maxWorkers": 8
  }
}
```

`type` は `DIRECT` / `YTDLP` / `IWARA` / `GALLERY`。URL 判定は部分一致で、
**より長いパターンが優先**されます（Python 版と同じ）。

## ビルド方法

Android Studio（推奨）でこのリポジトリのルート（Android プロジェクト）を開くと、
必要な SDK / Gradle Wrapper が自動で整い、そのままビルドできます。

### コマンドライン（検証済み）

Gradle Wrapper（`gradlew` / `gradlew.bat` / `gradle/wrapper/gradle-wrapper.jar`）は
リポジトリに同梱済みです。`gradle` コマンドが PATH に無くても、以下のように
**JDK と Android SDK の場所だけ**を環境変数で指定すればビルドできます。

```powershell
$env:JAVA_HOME   = 'C:\Program Files\Android\Android Studio\jbr'   # ★JDK 17〜21 を使う
$env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk"
$env:ANDROID_SDK_ROOT = $env:ANDROID_HOME

.\gradlew.bat :app:assembleDebug
```

生成物: `app/build/outputs/apk/debug/app-debug.apk`

> ⚠️ **JDK 22 以降ではビルドできません。** Kotlin 2.0 系のプラグインが
> JVM バージョン 22 以降を解釈できず（`IllegalArgumentException: 25.0.3` など）、
> ビルドスクリプトのコンパイル時点で失敗します。Android Studio 同梱の JBR が
> JDK 22 以降の場合は、JDK 17 / 21 を別途用意して `JAVA_HOME` をそこへ向けてください。
> （例: [Adoptium Temurin 21](https://adoptium.net/temurin/releases/?version=21) の zip を展開して使用）

### 必須環境

- JDK 17〜21（`JAVA_HOME` に設定）
- Android SDK: Platform 35 / Build-Tools 35.0.0
  （未インストールでも、AGP がライセンスに同意済みであれば自動ダウンロードします）
- Gradle: 8.9（Wrapper が自動取得）
- youtubedl-android はネイティブ `.so` を含むため ABI を 4 種指定済み（`app/build.gradle.kts`）。

> 📦 `assembleDebug` の APK は 4 ABI 分の `libpython.so` / `libffmpeg.so` を含むため
> 約 229 MB になります。配布用に小さくしたい場合は `ndk.abiFilters` を
> `arm64-v8a` / `armeabi-v7a` に絞るか、ABI スプリットの導入を検討してください。

## 既知の制限（Python 版との差分）

- Python の `.py` アドオンを動的実行することは Android では行いません
  （宣言的 JSON 定義 + 内蔵エンジンに置き換え）。
- `momonga_addon.py` のうち Selenium（Edge）を必要とする動的サイトは Android では未対応。
  静的 HTML サイト（momon-ga / ddd-smart 相当）は `GalleryDownloader` で取得できます。
- 本体の自動アップデート（`.py` 差し替え）はアプリストア配布が前提のため未実装。
  設定値（リポジトリ URL など）は保持のみ行います。

## ライセンス

Apache License 2.0（Python 版に準拠）

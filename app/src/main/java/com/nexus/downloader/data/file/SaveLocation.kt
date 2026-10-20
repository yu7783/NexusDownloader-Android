package com.nexus.downloader.data.file

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import android.webkit.MimeTypeMap
import com.nexus.downloader.R
import com.nexus.downloader.data.model.AppConfig
import com.nexus.downloader.data.model.StorageTarget
import java.io.File

/**
 * 保存先を扱うヘルパ。
 *
 * Android 10 以降は共有ストレージ（例: 内部ストレージ/Download）へ直接書き込めないため、
 * 既定ではアプリ専用領域にダウンロードする。ユーザーが設定画面で**フォルダを選んだ**
 * 場合は、ダウンロード完了後にそのフォルダ（SAF のツリー URI）へ移動する。
 */
object SaveLocation {

    /**
     * アプリ専用の保存先ルート（常に書き込める場所）。
     *
     * フォルダはここでは作らない（設定画面でフォルダ名を入力するたびに空フォルダが
     * 増えないようにするため）。実際の作成はダウンロード時の
     * [com.nexus.downloader.download.DownloadEnvironment.ensureDir] が行う。
     */
    fun appRootDir(context: Context, config: AppConfig): File {
        val base = when (config.storageTarget) {
            StorageTarget.EXTERNAL ->
                context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: context.filesDir

            StorageTarget.INTERNAL -> context.filesDir
        }
        val sub = sanitizeSubDir(config.downloadDir)
        return if (sub.isEmpty()) base else File(base, sub)
    }

    /**
     * サブフォルダ名を安全な相対パスに整える。
     *
     * 「..」や先頭の「/」を落として、アプリ専用領域の外へ出ないようにする。
     */
    fun sanitizeSubDir(name: String): String =
        name.replace('\\', '/')
            .split('/')
            .map { it.trim() }
            .filter { it.isNotEmpty() && it != "." && it != ".." }
            .joinToString("/")

    /** 設定で選ばれている保存先フォルダ（SAF）。未選択なら null。 */
    fun treeUri(config: AppConfig): Uri? =
        config.saveTreeUri?.takeIf { it.isNotBlank() }?.let { runCatching { Uri.parse(it) }.getOrNull() }

    /** 選択フォルダの表示名（取得できなければ URI の末尾）。 */
    fun treeName(context: Context, tree: Uri): String? = runCatching {
        val doc = rootDocument(tree)
        context.contentResolver
            .query(doc, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)
            ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
    }.getOrNull() ?: tree.lastPathSegment?.substringAfterLast(':')

    /** 現在の保存先の説明（設定画面の表示用）。 */
    fun describe(context: Context, config: AppConfig): String {
        val tree = treeUri(config) ?: return context.getString(R.string.save_location_app_folder)
        val name = treeName(context, tree)
        return if (name.isNullOrBlank()) {
            context.getString(R.string.save_location_selected)
        } else {
            context.getString(R.string.save_location_selected_named, name)
        }
    }

    /**
     * ダウンロード済みのファイル / フォルダを選択フォルダへ移動する。
     *
     * @return 移動できたら移動先の URI、失敗したら null（元の場所を維持する）。
     */
    fun move(context: Context, source: File, tree: Uri): Uri? {
        if (!source.exists()) return null
        val root = runCatching { rootDocument(tree) }.getOrNull() ?: return null
        return runCatching {
            val moved = if (source.isDirectory) {
                copyDirectory(context, source, root)
            } else {
                copyFile(context, source, root)
            }
            if (moved != null) source.deleteRecursively()
            moved
        }.getOrNull()
    }

    /** 拡張子から MIME タイプを推定する。 */
    fun mimeOf(file: File): String {
        val ext = file.extension.lowercase()
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
    }

    /** フォルダ自身（ツリーのルート）の document URI。 */
    private fun rootDocument(tree: Uri): Uri =
        DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))

    private fun copyFile(context: Context, source: File, parent: Uri): Uri? {
        val dest = createChild(context, parent, source.name, mimeOf(source)) ?: return null
        context.contentResolver.openOutputStream(dest, "wt")?.use { output ->
            source.inputStream().use { input -> input.copyTo(output) }
        } ?: return null
        return dest
    }

    private fun copyDirectory(context: Context, source: File, parent: Uri): Uri? {
        val dir = createChild(context, parent, source.name, DocumentsContract.Document.MIME_TYPE_DIR)
            ?: return null
        source.listFiles()?.forEach { child ->
            if (child.isDirectory) copyDirectory(context, child, dir) else copyFile(context, child, dir)
        }
        return dir
    }

    private fun createChild(context: Context, parent: Uri, name: String, mime: String): Uri? =
        runCatching {
            DocumentsContract.createDocument(context.contentResolver, parent, mime, name)
        }.getOrNull()
}
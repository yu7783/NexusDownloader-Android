package com.nexus.downloader.data.file

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import com.nexus.downloader.R
import java.io.File

/**
 * ダウンロード済みファイルを「開く / 共有」するためのユーティリティ。
 *
 * アプリ専用フォルダのファイルは FileProvider 経由の `content://` URI を発行し、
 * ユーザーが選んだ保存先フォルダ（SAF）のファイルはその URI をそのまま使う。
 */
object FileOpener {

    /** 保存済みパス（絶対パス / content URI）を開く。フォルダならファイルマネージャに委ねる。 */
    fun open(context: Context, path: String): Boolean {
        if (path.startsWith(CONTENT_SCHEME)) return openDocument(context, path)
        val file = File(path)
        if (!file.exists()) return false
        val intent = if (file.isDirectory) {
            Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uriOf(context, file), "resource/folder")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        } else {
            Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uriOf(context, file), mimeOf(file))
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }
        return runCatching { context.startActivity(intent) }.isSuccess
    }

    /** 保存済みファイル（絶対パス / content URI）を共有する。 */
    fun share(context: Context, path: String): Boolean {
        val uri: Uri
        val mime: String
        if (path.startsWith(CONTENT_SCHEME)) {
            uri = Uri.parse(path)
            mime = mimeOfName(path)
        } else {
            val file = File(path)
            if (!file.exists()) return false
            uri = uriOf(context, file)
            mime = if (file.isDirectory) "*/*" else mimeOf(file)
        }
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(intent, context.getString(R.string.action_share))
        return runCatching { context.startActivity(chooser) }.isSuccess
    }

    /** 保存先フォルダ（SAF）のファイルを開く。 */
    private fun openDocument(context: Context, path: String): Boolean {
        val uri = runCatching { Uri.parse(path) }.getOrNull() ?: return false
        val mime = mimeOfName(path)
        val asFolder = mime.isBlank()
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(
                uri,
                if (asFolder) DocumentsContract.Document.MIME_TYPE_DIR else mime,
            )
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return runCatching { context.startActivity(intent) }.isSuccess
    }

    private fun uriOf(context: Context, file: File) =
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)

    private fun mimeOf(file: File): String {
        val ext = file.extension.lowercase()
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "*/*"
    }

    /** URI のパス末尾から MIME タイプを推定する（フォルダなら空文字）。 */
    private fun mimeOfName(path: String): String {
        val name = Uri.decode(path.substringAfterLast('/'))
        val ext = name.substringAfterLast('.', "").lowercase()
        if (ext.isEmpty() || ext == name.lowercase()) return ""
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "*/*"
    }

    private const val CONTENT_SCHEME = "content://"
}

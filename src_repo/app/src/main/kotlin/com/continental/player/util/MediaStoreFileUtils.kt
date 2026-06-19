package com.continental.player.util

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.documentfile.provider.DocumentFile
import java.io.File
import java.io.FileInputStream

/**
 * Moves a file that yt-dlp/ffmpeg just finished writing into app-private storage into a
 * place the user (and other apps) can actually see — either a public MediaStore collection
 * (zero permissions needed on API 29+) or a folder the user picked once via the Storage
 * Access Framework. Either way, scoped storage rules are respected: we never touch a raw
 * filesystem path outside our own sandbox without going through one of these two doors.
 */
object MediaStoreFileUtils {

    enum class Collection { MOVIES, DOWNLOADS }

    fun publishToMediaStore(
        context: Context,
        sourceFile: File,
        displayName: String,
        mimeType: String,
        collection: Collection,
        subfolder: String
    ): Uri? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            publishModern(context, sourceFile, displayName, mimeType, collection, subfolder)
        } else {
            publishLegacy(context, sourceFile, displayName, collection, subfolder)
        }
    }

    private fun publishModern(
        context: Context,
        sourceFile: File,
        displayName: String,
        mimeType: String,
        collection: Collection,
        subfolder: String
    ): Uri? {
        val externalUri = when (collection) {
            Collection.MOVIES -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            Collection.DOWNLOADS -> MediaStore.Downloads.EXTERNAL_CONTENT_URI
        }
        val relativeBase = when (collection) {
            Collection.MOVIES -> Environment.DIRECTORY_MOVIES
            Collection.DOWNLOADS -> Environment.DIRECTORY_DOWNLOADS
        }
        val relativePath = if (subfolder.isBlank()) "$relativeBase/" else "$relativeBase/$subfolder/"

        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
            put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }

        val resolver = context.contentResolver
        val itemUri = resolver.insert(externalUri, values) ?: return null

        return try {
            resolver.openOutputStream(itemUri)?.use { out ->
                FileInputStream(sourceFile).use { input -> input.copyTo(out) }
            } ?: return null

            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            resolver.update(itemUri, values, null, null)
            sourceFile.delete()
            itemUri
        } catch (e: Exception) {
            resolver.delete(itemUri, null, null)
            null
        }
    }

    /** API 26-28 path: legacy storage, no scoped-storage IS_PENDING dance, needs WRITE_EXTERNAL_STORAGE. */
    @Suppress("DEPRECATION")
    private fun publishLegacy(
        context: Context,
        sourceFile: File,
        displayName: String,
        collection: Collection,
        subfolder: String
    ): Uri? {
        val baseDir = Environment.getExternalStoragePublicDirectory(
            if (collection == Collection.MOVIES) Environment.DIRECTORY_MOVIES
            else Environment.DIRECTORY_DOWNLOADS
        )
        val targetDir = if (subfolder.isBlank()) baseDir else File(baseDir, subfolder)
        if (!targetDir.exists()) targetDir.mkdirs()

        val destFile = File(targetDir, displayName)
        return try {
            FileInputStream(sourceFile).use { input ->
                destFile.outputStream().use { output -> input.copyTo(output) }
            }
            sourceFile.delete()
            MediaScannerConnection.scanFile(
                context, arrayOf(destFile.absolutePath), null, null
            )
            Uri.fromFile(destFile)
        } catch (e: Exception) {
            null
        }
    }

    /** Publishes into a folder the user explicitly granted via ACTION_OPEN_DOCUMENT_TREE. */
    fun publishToCustomTree(
        context: Context,
        sourceFile: File,
        displayName: String,
        mimeType: String,
        treeUriString: String
    ): Uri? {
        return try {
            val treeUri = Uri.parse(treeUriString)
            val treeDoc = DocumentFile.fromTreeUri(context, treeUri) ?: return null
            val newDoc = treeDoc.createFile(mimeType, displayName) ?: return null
            context.contentResolver.openOutputStream(newDoc.uri)?.use { out ->
                FileInputStream(sourceFile).use { input -> input.copyTo(out) }
            } ?: return null
            sourceFile.delete()
            newDoc.uri
        } catch (e: Exception) {
            null
        }
    }
}

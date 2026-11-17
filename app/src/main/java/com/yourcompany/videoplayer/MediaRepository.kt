package com.yourcompany.videoplayer

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import java.io.File
import java.util.ArrayList

class MediaRepository(val context: Context) {

    private fun standardizePath(path: String): String {
        if (path.isEmpty() || path == "STORAGE_ROOT") return path
        var p = path.replace("/mnt/media_rw/", "/storage/")
        while (p.contains("//")) p = p.replace("//", "/")
        if (p.length > 1 && p.endsWith("/")) p = p.substring(0, p.length - 1)
        return p
    }

    fun getMediaFromFolder(folderPath: String = "", isVideo: Boolean): ArrayList<MediaFile> {
        val list = ArrayList<MediaFile>()
        val foundPaths = mutableSetOf<String>()
        val stdFolderPath = standardizePath(folderPath)

        val baseUri = if (isVideo) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        val urisToQuery = mutableListOf<Uri>(baseUri)
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                val volumeNames = MediaStore.getExternalVolumeNames(context)
                for (vol in volumeNames) {
                    if (vol != MediaStore.VOLUME_EXTERNAL_PRIMARY) {
                        urisToQuery.add(if (isVideo) MediaStore.Video.Media.getContentUri(vol) else MediaStore.Audio.Media.getContentUri(vol))
                    }
                }
            } catch (e: Exception) { }
        }

        val projection = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DATA,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.DURATION,
            MediaStore.MediaColumns.DATE_MODIFIED
        )

        for (uri in urisToQuery.distinct()) {
            try {
                val cursor = context.contentResolver.query(uri, projection, null, null, null)
                cursor?.use {
                    val dataIdx = it.getColumnIndexOrThrow(MediaStore.MediaColumns.DATA)
                    val idIdx = it.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                    val nameIdx = it.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                    val sizeIdx = it.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
                    val durationIdx = it.getColumnIndexOrThrow(MediaStore.MediaColumns.DURATION)

                    while (it.moveToNext()) {
                        val originalPath = it.getString(dataIdx) ?: ""
                        if (originalPath.isEmpty()) continue
                        
                        val stdPath = standardizePath(originalPath)
                        if (foundPaths.contains(stdPath)) continue

                        if (stdFolderPath.isNotEmpty() && stdFolderPath != "STORAGE_ROOT") {
                            val lowerStdPath = stdPath.lowercase()
                            val lowerFolder = stdFolderPath.lowercase()
                            if (!(lowerStdPath.startsWith(lowerFolder + "/") || lowerStdPath == lowerFolder)) {
                                continue
                            }
                        }

                        var duration = it.getLong(durationIdx)
                        if (duration <= 0) duration = getDurationManually(stdPath)

                        list.add(MediaFile(
                            id = it.getLong(idIdx),
                            path = stdPath,
                            title = it.getString(nameIdx) ?: "Unknown",
                            size = it.getLong(sizeIdx).let { s -> if (s > 0) s else File(stdPath).length() },
                            duration = duration,
                            isVideo = isVideo
                        ))
                        foundPaths.add(stdPath)
                    }
                }
            } catch (e: Exception) { }
        }

        if (stdFolderPath.isNotEmpty() && stdFolderPath != "STORAGE_ROOT") {
            scanManualRecursive(File(stdFolderPath), list, foundPaths, 0, isVideo)
        }

        list.sortWith { o1, o2 -> naturalCompare(o1.title.lowercase(), o2.title.lowercase()) }
        return list
    }

    private fun getDurationManually(path: String): Long {
        var duration = 0L
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(path)
            duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLong() ?: 0L
        } catch (e: Exception) { } finally {
            try { retriever.release() } catch (e: Exception) {}
        }
        return duration
    }

    private fun scanManualRecursive(dir: File, list: MutableList<MediaFile>, foundPaths: MutableSet<String>, depth: Int, isVideo: Boolean) {
        if (!dir.exists() || !dir.isDirectory || depth > 2) return 
        val exts = if (isVideo) setOf("mp4", "mkv", "avi", "mov", "wmv", "flv", "webm", "3gp")
                   else setOf("mp3", "wav", "m4a", "ogg", "flac")
        try {
            dir.listFiles()?.forEach { file ->
                val stdPath = standardizePath(file.absolutePath)
                if (file.isDirectory) {
                    if (!file.name.startsWith(".")) scanManualRecursive(file, list, foundPaths, depth + 1, isVideo)
                } else if (file.isFile && exts.contains(file.extension.lowercase())) {
                    if (!foundPaths.contains(stdPath)) {
                        list.add(MediaFile(
                            id = file.hashCode().toLong(), 
                            path = stdPath, 
                            title = file.name, 
                            size = file.length(), 
                            duration = getDurationManually(stdPath),
                            isVideo = isVideo
                        ))
                        foundPaths.add(stdPath)
                    }
                }
            }
        } catch (e: Exception) { }
    }

    private fun naturalCompare(s1: String, s2: String): Int {
        val n1 = s1.length; val n2 = s2.length
        var i1 = 0; var i2 = 0
        while (i1 < n1 && i2 < n2) {
            val c1 = s1[i1]; val c2 = s2[i2]
            if (Character.isDigit(c1) && Character.isDigit(c2)) {
                var num1 = ""; while (i1 < n1 && Character.isDigit(s1[i1])) { num1 += s1[i1]; i1++ }
                var num2 = ""; while (i2 < n2 && Character.isDigit(s2[i2])) { num2 += s2[i2]; i2++ }
                val res = try { num1.toLong().compareTo(num2.toLong()) } catch (e: Exception) { 0 }
                if (res != 0) return res
            } else {
                if (c1 != c2) return c1.compareTo(c2)
                i1++; i2++
            }
        }
        return n1 - n2
    }
}

data class MediaFile(
    val id: Long,
    val path: String,
    val title: String,
    val size: Long,
    val duration: Long,
    val isVideo: Boolean
) : java.io.Serializable

package com.yourcompany.videoplayer

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.ParcelFileDescriptor
import android.os.ProxyFileDescriptorCallback
import android.os.storage.StorageManager
import android.util.LruCache
import androidx.annotation.RequiresApi
import java.io.File
import java.io.RandomAccessFile
import kotlin.math.max

class DecryptionProvider : ContentProvider() {
    private lateinit var handlerThread: HandlerThread
    private lateinit var backgroundHandler: Handler
    
    private val XOR_KEY = 0x69.toByte()
    private val signaturePositionCache = LruCache<String, Long>(1000)

    override fun onCreate(): Boolean {
        handlerThread = HandlerThread("DecryptionThread")
        handlerThread.start()
        backgroundHandler = Handler(handlerThread.looper)
        return true
    }

    override fun query(u: Uri, p: Array<String>?, s: String?, sa: Array<String>?, o: String?) = null
    override fun getType(u: Uri) = "video/*"
    override fun insert(u: Uri, v: ContentValues?) = null
    override fun delete(u: Uri, s: String?, sa: Array<String>?) = 0
    override fun update(u: Uri, v: ContentValues?, s: String?, sa: Array<String>?) = 0

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
        val path = uri.path ?: return null
        val file = File(path)
        if (!file.exists()) return null

        val prefs = context?.getSharedPreferences("SETTINGS", Context.MODE_PRIVATE)
        val signatureStr = prefs?.getString("SECURITY_SIGNATURE", "HAMZA") ?: "HAMZA"
        val signatureBytes = signatureStr.toByteArray()

        val signaturePos = findSignatureSync(file, signatureBytes)
        
        if (signaturePos != -1L && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            return openDecryptedStream(file, signaturePos)
        }
        
        return try {
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        } catch (e: Exception) { null }
    }

    private fun findSignatureSync(file: File, signature: ByteArray): Long {
        val filePath = file.absolutePath
        val cached = signaturePositionCache.get(filePath)
        if (cached != null) return cached

        return try {
            RandomAccessFile(file, "r").use { raf ->
                val scanLimit = 1024 * 1024 * 50L 
                val bufferSize = 1024 * 256
                val buffer = ByteArray(bufferSize)
                val sigSize = signature.size
                var totalRead = 0L

                while (totalRead < scanLimit) {
                    raf.seek(totalRead)
                    val readCount = raf.read(buffer)
                    if (readCount < sigSize) break
                    
                    for (i in 0 .. (readCount - sigSize)) {
                        var match = true
                        for (j in 0 until sigSize) {
                            if (buffer[i + j] != signature[j]) {
                                match = false
                                break
                            }
                        }
                        if (match) {
                            val pos = totalRead + i + sigSize
                            signaturePositionCache.put(filePath, pos)
                            return pos
                        }
                    }
                    totalRead += (readCount - sigSize + 1)
                }
                -1L
            }
        } catch (e: Exception) { -1L }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun openDecryptedStream(file: File, signaturePos: Long): ParcelFileDescriptor? {
        val storageManager = context?.getSystemService(Context.STORAGE_SERVICE) as? StorageManager
        val raf = RandomAccessFile(file, "r")
        val dataSize = file.length() - signaturePos
        
        return try {
            storageManager?.openProxyFileDescriptor(
                ParcelFileDescriptor.MODE_READ_ONLY,
                object : ProxyFileDescriptorCallback() {
                    override fun onGetSize(): Long = dataSize
                    override fun onRead(offset: Long, size: Int, data: ByteArray): Int {
                        synchronized(raf) {
                            try {
                                raf.seek(signaturePos + offset)
                                val bytesRead = raf.read(data, 0, size)
                                if (bytesRead > 0) {
                                    for (i in 0 until bytesRead) {
                                        data[i] = (data[i].toInt() xor XOR_KEY.toInt()).toByte()
                                    }
                                }
                                return max(0, bytesRead)
                            } catch (e: Exception) { return 0 }
                        }
                    }
                    override fun onRelease() { try { raf.close() } catch (e: Exception) {} }
                },
                backgroundHandler
            )
        } catch (e: Exception) {
            try { raf.close() } catch (ex: Exception) {}
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        }
    }
}

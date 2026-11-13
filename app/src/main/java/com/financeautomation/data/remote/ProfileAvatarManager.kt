package com.financeautomation.data.remote

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.financeautomation.core.utils.Diagnostics
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.net.URI
import java.util.concurrent.TimeUnit

/**
 * Manages downloading, local storage, validation, and lifecycle of the Google account profile avatar.
 */
class ProfileAvatarManager(
    private val context: Context,
    private val okHttpClient: OkHttpClient = defaultClient,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) {

    /**
     * Downloads the profile photo from [rawUrl], validates security constraints,
     * downsamples to <= 256px, and saves to private internal storage.
     */
    suspend fun downloadAndSaveAvatar(rawUrl: String): Boolean = withContext(ioDispatcher) {
        if (!isValidPhotoUrl(rawUrl)) {
            Diagnostics.record("ProfileAvatarManager", "Rejected untrusted avatar url: $rawUrl")
            return@withContext false
        }

        val targetUrl = formatPhotoUrl(rawUrl)
        try {
            val request = Request.Builder()
                .url(targetUrl)
                .build()

            okHttpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Diagnostics.record("ProfileAvatarManager", "HTTP ${response.code} downloading avatar")
                    return@withContext false
                }

                val body = response.body ?: return@withContext false
                val contentLength = body.contentLength()
                if (contentLength > MAX_BODY_BYTES) {
                    Diagnostics.record("ProfileAvatarManager", "Avatar payload exceeded limit: $contentLength bytes")
                    return@withContext false
                }

                val bytes = body.byteStream().use { input ->
                    val buffer = ByteArrayOutputStream()
                    val data = ByteArray(4096)
                    var count = 0
                    var total = 0
                    while (input.read(data).also { count = it } != -1) {
                        total += count
                        if (total > MAX_BODY_BYTES) {
                            Diagnostics.record("ProfileAvatarManager", "Avatar stream exceeded 512 KB limit")
                            return@withContext false
                        }
                        buffer.write(data, 0, count)
                    }
                    buffer.toByteArray()
                }

                if (bytes.isEmpty()) return@withContext false

                val options = BitmapFactory.Options().apply {
                    inJustDecodeBounds = true
                }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
                if (options.outWidth <= 0 || options.outHeight <= 0) {
                    return@withContext false
                }

                var sampleSize = 1
                while ((options.outWidth / sampleSize) > MAX_IMAGE_DIMENSION ||
                    (options.outHeight / sampleSize) > MAX_IMAGE_DIMENSION
                ) {
                    sampleSize *= 2
                }

                val decodeOptions = BitmapFactory.Options().apply {
                    inSampleSize = sampleSize
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                }
                val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, decodeOptions) ?: return@withContext false

                val destFile = File(context.filesDir, AVATAR_FILE_NAME)
                val tempFile = File(context.filesDir, "$AVATAR_FILE_NAME.tmp")
                FileOutputStream(tempFile).use { out ->
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                    out.flush()
                }

                if (tempFile.renameTo(destFile)) {
                    Diagnostics.record("ProfileAvatarManager", "Successfully saved avatar: ${destFile.length()} bytes")
                    true
                } else {
                    tempFile.copyTo(destFile, overwrite = true)
                    tempFile.delete()
                    true
                }
            }
        } catch (e: Exception) {
            Diagnostics.record("ProfileAvatarManager", "Failed downloading avatar: ${e.javaClass.simpleName}")
            false
        }
    }

    /**
     * Deletes the local avatar file on sign out or disconnect.
     */
    fun deleteAvatar(): Boolean {
        val file = File(context.filesDir, AVATAR_FILE_NAME)
        return if (file.exists()) {
            file.delete()
        } else {
            false
        }
    }

    /**
     * Checks if a cached avatar file currently exists.
     */
    fun hasAvatar(): Boolean {
        val file = File(context.filesDir, AVATAR_FILE_NAME)
        return file.exists() && file.length() > 0L
    }

    /**
     * Returns the private avatar file path.
     */
    fun getAvatarFile(): File = File(context.filesDir, AVATAR_FILE_NAME)

    companion object {
        const val AVATAR_FILE_NAME = "google_avatar.png"
        const val MAX_BODY_BYTES = 512 * 1024L
        const val MAX_IMAGE_DIMENSION = 256

        private val defaultClient: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .build()
        }

        /**
         * Validates that the URL uses HTTPS and points strictly to a googleusercontent.com host.
         */
        fun isValidPhotoUrl(url: String?): Boolean {
            if (url.isNullOrBlank()) return false
            val uri = try {
                URI.create(url)
            } catch (_: Exception) {
                return false
            }

            if (!"https".equals(uri.scheme, ignoreCase = true)) {
                return false
            }

            val host = uri.host?.lowercase() ?: return false
            return host == "googleusercontent.com" || host.endsWith(".googleusercontent.com")
        }

        /**
         * Formats URL to request exactly `=s128-c` size variant.
         */
        fun formatPhotoUrl(rawUrl: String): String {
            return if (rawUrl.contains("=")) {
                rawUrl.replace(Regex("=s\\d+(-c)?.*$"), "=s128-c")
            } else {
                "$rawUrl=s128-c"
            }
        }
    }
}

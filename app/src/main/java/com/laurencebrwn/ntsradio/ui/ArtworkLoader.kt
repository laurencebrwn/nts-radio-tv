package com.laurencebrwn.ntsradio.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.widget.ImageView
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * Tiny artwork loader (the app only ever shows two or three images, so a
 * memory cache and a couple of worker threads are plenty).
 */
object ArtworkLoader {

    private const val MAX_SIZE_KB = 32 * 1024
    private const val MAX_IMAGE_DIMENSION = 1024

    private val cache = object : LruCache<String, Bitmap>(MAX_SIZE_KB) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount / 1024
    }
    private val executor = Executors.newFixedThreadPool(2) { runnable ->
        Thread(runnable, "nts-artwork").apply { isDaemon = true }
    }
    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * @param maxDimension longest edge of the decoded bitmap. Pass a small value
     * (e.g. 48) for the blurred-looking backdrop: upscaling a tiny bitmap is
     * cheap and avoids [android.graphics.RenderEffect] blur, which crashes the
     * GPU stack on some TVs and emulators.
     */
    fun load(
        url: String?,
        imageView: ImageView,
        crossfade: Boolean = true,
        maxDimension: Int = MAX_IMAGE_DIMENSION,
    ) {
        if (url.isNullOrBlank()) {
            imageView.tag = null
            imageView.setImageDrawable(null)
            return
        }
        val key = if (maxDimension == MAX_IMAGE_DIMENSION) url else "$url@$maxDimension"
        if (imageView.tag == key && imageView.drawable != null) return
        imageView.tag = key

        cache.get(key)?.let { bitmap ->
            show(imageView, key, bitmap, crossfade)
            return
        }

        executor.execute {
            val bitmap = download(url, maxDimension)
            if (bitmap == null) return@execute
            cache.put(key, bitmap)
            mainHandler.post { show(imageView, key, bitmap, crossfade) }
        }
    }

    private fun show(imageView: ImageView, key: String, bitmap: Bitmap, crossfade: Boolean) {
        if (imageView.tag != key) return
        imageView.setImageBitmap(bitmap)
        if (crossfade) {
            imageView.alpha = 0f
            imageView.animate().alpha(1f).setDuration(280L).start()
        }
    }

    private fun download(url: String, maxDimension: Int = MAX_IMAGE_DIMENSION): Bitmap? {
        var connection: HttpURLConnection? = null
        return try {
            connection = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 10_000
                readTimeout = 15_000
                setRequestProperty("User-Agent", "NTSRadioTV/1.0 (Android TV)")
            }
            if (connection.responseCode != HttpURLConnection.HTTP_OK) return null

            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            connection.inputStream.use { BitmapFactory.decodeStream(it, null, bounds) }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

            var sampleSize = 1
            while (bounds.outWidth / sampleSize > maxDimension ||
                bounds.outHeight / sampleSize > maxDimension
            ) {
                sampleSize *= 2
            }

            connection = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 10_000
                readTimeout = 15_000
                setRequestProperty("User-Agent", "NTSRadioTV/1.0 (Android TV)")
            }
            val options = BitmapFactory.Options().apply { inSampleSize = sampleSize }
            connection.inputStream.use { BitmapFactory.decodeStream(it, null, options) }
        } catch (e: Exception) {
            null
        } finally {
            connection?.disconnect()
        }
    }
}

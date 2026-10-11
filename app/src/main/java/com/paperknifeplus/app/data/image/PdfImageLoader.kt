package com.paperknifeplus.app.data.image

import android.content.Context
import coil.ImageLoader
import coil.key.Keyer
import coil.memory.MemoryCache
import coil.request.Options
import java.security.MessageDigest

/**
 * The process-wide Coil loader for PDF page thumbnails. Screens that want a different cache size derive one with [ImageLoader.newBuilder],
 * which shares the fetcher and keyer; there is no disk cache, as rendered pages are cheap to redo and nothing ever read the old one.
 */
object PdfImageLoader {
    @Volatile
    private var instance: ImageLoader? = null

    fun get(context: Context): ImageLoader = instance ?: synchronized(this) {
        instance ?: build(context.applicationContext).also { instance = it }
    }

    private fun build(app: Context): ImageLoader = ImageLoader.Builder(app)
        .components {
            add(PdfPageKeyer())
            add(PdfPageFetcher.Factory(app))
        }
        .memoryCache { MemoryCache.Builder(app).maxSizePercent(0.25).build() }
        .crossfade(false)
        .build()
}

/**
 * Memory-cache key for a [PdfPageRequest]. Every field but the password and the scheduling priority is part of it as written by the data
 * class, so fields added later are keyed automatically; the password goes in only as a digest, keeping it out of the cache's key strings.
 */
class PdfPageKeyer : Keyer<PdfPageRequest> {
    override fun key(data: PdfPageRequest, options: Options): String {
        val password = data.password?.let { pw ->
            MessageDigest.getInstance("SHA-256").digest(pw.toByteArray()).take(8).joinToString("") { "%02x".format(it) }
        }
        return "${data.copy(password = null, priority = 0)}#${password ?: "-"}"
    }
}

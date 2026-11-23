package tv.cleanhome.launcher

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache

/** Provides a memory-conscious Coil image loader (the target device has very little RAM). */
class CleanHomeApp : Application(), ImageLoaderFactory {

    override fun newImageLoader(): ImageLoader =
        ImageLoader.Builder(this)
            .memoryCache {
                MemoryCache.Builder(this)
                    .maxSizePercent(MEMORY_CACHE_PERCENT)
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve("poster_cache"))
                    .maxSizeBytes(DISK_CACHE_BYTES)
                    .build()
            }
            // Poster servers often send no-cache headers, yet the images rarely change.
            .respectCacheHeaders(false)
            .build()

    companion object {
        /** Logcat tag used across the app: `adb logcat -s CleanHome`. */
        const val TAG = "CleanHome"

        private const val MEMORY_CACHE_PERCENT = 0.15
        private const val DISK_CACHE_BYTES = 48L * 1024 * 1024
    }
}

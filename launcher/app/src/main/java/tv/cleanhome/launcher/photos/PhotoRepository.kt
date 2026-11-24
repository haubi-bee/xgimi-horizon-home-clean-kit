package tv.cleanhome.launcher.photos

import android.os.Environment
import android.util.Log
import tv.cleanhome.launcher.CleanHomeApp.Companion.TAG
import java.io.File

/**
 * Photos from /sdcard/Pictures/CleanHome (recursive). Random order without direct repeats; the
 * folder is re-read on every cycle and as soon as a photo of the current cycle is gone. Blocking;
 * call on an IO thread.
 */
class PhotoRepository(private val root: File = defaultRoot()) {
    private val queue = ShuffleQueue<File>()
    private var lastDiagnosis: String? = null

    fun next(): File? {
        val file = queue.next(::scan) ?: return null
        if (file.isFile) return file
        // The folder changed since it was read (for example a photo sync replaced the files):
        // drop the outdated order and read the folder again right away.
        queue.startNewCycle()
        return queue.next(::scan)
    }

    private fun scan(): List<File> {
        val files = try {
            if (root.listFiles() == null) {
                diagnose(
                    if (root.exists()) "Photo folder $root is not readable (READ_EXTERNAL_STORAGE granted?)"
                    else "Photo folder $root does not exist",
                )
                return emptyList()
            }
            root.walkTopDown()
                .onEnter { !it.name.startsWith(".") }
                .filter { it.isFile && !it.name.startsWith(".") && it.extension.lowercase() in EXTENSIONS }
                .toList()
        } catch (e: SecurityException) {
            diagnose("No access to $root: ${e.message}")
            return emptyList()
        }
        diagnose(if (files.isEmpty()) "Photo folder $root contains no images" else null)
        return files
    }

    /** Logs every change of state exactly once instead of on every retry. */
    private fun diagnose(message: String?) {
        if (message == lastDiagnosis) return
        lastDiagnosis = message
        if (message != null) Log.w(TAG, message)
    }

    companion object {
        private const val FOLDER_NAME = "CleanHome"
        private val EXTENSIONS = setOf("jpg", "jpeg", "png", "webp")

        @Suppress("DEPRECATION")
        fun defaultRoot(): File =
            File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), FOLDER_NAME)
    }
}

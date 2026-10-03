package ru.dm1sh.rechka.model

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import java.io.File
import java.security.MessageDigest

/** GigaAM v3 files installed from a user-selected SAF folder. */
object GigaAmModel {
    const val ENCODER = "gigaam_v3_e2e_rnnt_encoder_int8.onnx"
    const val DECODER = "gigaam_v3_e2e_rnnt_decoder.onnx"
    const val JOINER = "gigaam_v3_e2e_rnnt_joint.onnx"
    const val TOKENS = "gigaam_v3_e2e_rnnt_tokens.txt"

    data class ModelFile(val name: String, val sizeBytes: Long, val sha256: String)

    private const val INSTALL_DIR = "models/gigaam-v3-e2e-rnnt"

    val FILES = listOf(
        ModelFile(ENCODER, 318_995_997L, "2cac62d0c270bd128f898f2be1a2d34780d524a6e9483888ebac7b00f97410f1"),
        ModelFile(DECODER, 4_600_058L, "781971998e6a355d6a714f6932a30eab295e7ba0d14fd7e0f78c83b87e811860"),
        ModelFile(JOINER, 2_712_896L, "602ff7017a93311aad34df1437c8d7f49911353c13d6eae7a6ee7b041339465c"),
        ModelFile(TOKENS, 13_353L, "7ddf22514c42c531358182c81446a8159771e9921019f09ae743ea622d40221d"),
    )

    enum class CopyResult { SUCCESS, MISSING, INVALID, UNREADABLE }

    fun modelDir(context: Context): File =
        File(context.filesDir, INSTALL_DIR).also { it.mkdirs() }

    /** Fast check used by recording paths; full hashes are checked after installation/startup. */
    fun isInstalled(context: Context): Boolean = FILES.all {
        val file = File(modelDir(context), it.name)
        file.isFile && file.length() == it.sizeBytes
    }

    /** Full local validation, performed off the main thread at app start. */
    fun validateInstalled(context: Context): Boolean = FILES.all {
        val file = File(modelDir(context), it.name)
        file.isFile && file.length() == it.sizeBytes && sha256(file) == it.sha256
    }

    /** Reads only the four required direct children of the selected folder. */
    fun copyFromSelectedFolder(context: Context, treeUri: Uri): CopyResult {
        val folder = DocumentFile.fromTreeUri(context, treeUri) ?: return CopyResult.UNREADABLE
        val selected = FILES.associateBy { it.name }
        val files = folder.listFiles().filter { it.isFile && selected.containsKey(it.name) }
            .associateBy { it.name }
        if (files.size != FILES.size) return CopyResult.MISSING

        val dir = modelDir(context)
        val staged = mutableListOf<File>()
        try {
            for (expected in FILES) {
                val source = files[expected.name] ?: return CopyResult.MISSING
                if (source.length() != expected.sizeBytes) return CopyResult.INVALID
                val sourceHash = context.contentResolver.openInputStream(source.uri)?.use(::sha256)
                    ?: return CopyResult.UNREADABLE
                if (sourceHash != expected.sha256) return CopyResult.INVALID

                val temporary = File(dir, "${expected.name}.part")
                temporary.delete()
                context.contentResolver.openInputStream(source.uri)?.use { input ->
                    temporary.outputStream().use { output -> input.copyTo(output) }
                } ?: return CopyResult.UNREADABLE
                if (temporary.length() != expected.sizeBytes || sha256(temporary) != expected.sha256) {
                    temporary.delete()
                    return CopyResult.INVALID
                }
                staged += temporary
            }
            staged.forEach { temporary ->
                val final = File(dir, temporary.name.removeSuffix(".part"))
                if (final.exists()) final.delete()
                check(temporary.renameTo(final))
            }
            return CopyResult.SUCCESS
        } catch (_: SecurityException) {
            return CopyResult.UNREADABLE
        } catch (_: Exception) {
            return CopyResult.INVALID
        } finally {
            dir.listFiles()?.filter { it.name.endsWith(".part") }?.forEach { it.delete() }
        }
    }

    private fun sha256(file: File): String = file.inputStream().use(::sha256)

    private fun sha256(input: java.io.InputStream): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(1024 * 1024)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}

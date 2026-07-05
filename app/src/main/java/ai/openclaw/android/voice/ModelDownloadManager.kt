package ai.openclaw.android.voice

import android.content.Context
import android.os.Environment
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Manages downloading and verifying sherpa-onnx models.
 *
 * Models are stored in external storage to avoid occupying app internal space.
 * Supports resumable downloads (HTTP Range) and SHA256 verification.
 */
object ModelDownloadManager {

    private const val TAG = "ModelDownloadManager"

    /** Model storage directory on external storage. */
    fun getModelBaseDir(context: Context): File {
        val externalDir = context.getExternalFilesDir(null)
        return File(externalDir ?: context.filesDir, "models")
    }

    fun getSttModelDir(context: Context): File = File(getModelBaseDir(context), "stt")
    fun getTtsModelDir(context: Context): File = File(getModelBaseDir(context), "tts")

    /**
     * Model registry entry.
     */
    data class ModelInfo(
        val id: String,
        val name: String,
        val description: String,
        val downloadUrl: String,
        val sha256: String,
        val sizeBytes: Long,
        val extractTarget: File,
        val requiredFiles: List<String>,
    )

    /**
     * Download progress state.
     */
    data class DownloadState(
        val modelId: String,
        val status: Status,
        val progress: Float = 0f,        // 0.0 to 1.0
        val downloadedBytes: Long = 0,
        val totalBytes: Long = 0,
        val error: String? = null,
    ) {
        enum class Status { Idle, Downloading, Extracting, Verifying, Complete, Failed }
    }

    /**
     * Available models.
     * Note: URLs and SHA256 should be verified before production use.
     */
    private const val BASE_RELEASE = "https://github.com/k2-fsa/sherpa-onnx/releases/download"

    fun getAvailableModels(context: Context): List<ModelInfo> {
        val sttDir = getSttModelDir(context)
        val ttsDir = getTtsModelDir(context)

        return listOf(
            // STT: sherpa-onnx-streaming-zipformer-zh-14M (compact Chinese, ~70MB)
            ModelInfo(
                id = "stt-zipformer-zh-14m",
                name = "中文语音识别 (Zipformer 14M)",
                description = "轻量级中文流式语音识别模型，约70MB",
                downloadUrl = "$BASE_RELEASE/asr-models/sherpa-onnx-streaming-zipformer-zh-14M-2023-02-23.tar.bz2",
                sha256 = "2cbd71b640d9c37d3784f29367333a4577b0398b62e9deeed418170b081cba8b",
                sizeBytes = 70 * 1024 * 1024,
                extractTarget = sttDir,
                requiredFiles = listOf(
                    "encoder-epoch-99-avg-1.int8.onnx",
                    "decoder-epoch-99-avg-1.onnx",
                    "joiner-epoch-99-avg-1.int8.onnx",
                    "tokens.txt",
                ),
            ),
            // STT: sherpa-onnx-streaming-paraformer-bilingual-zh-en (bilingual, ~240MB)
            ModelInfo(
                id = "stt-paraformer-bilingual",
                name = "中英双语语音识别 (Paraformer)",
                description = "中英双语流式语音识别，约240MB，识别质量更高",
                downloadUrl = "$BASE_RELEASE/asr-models/sherpa-onnx-streaming-paraformer-bilingual-zh-en.tar.bz2",
                sha256 = "5d44d87b1a14665301d58b735578524b4bde62688d0b5e6d21e4820170c7364d",
                sizeBytes = 366 * 1024 * 1024,
                extractTarget = sttDir,
                requiredFiles = listOf(
                    "encoder.int8.onnx",
                    "decoder.int8.onnx",
                    "tokens.txt",
                ),
            ),
            // TTS: vits-melo-tts-zh_en (Chinese+English, ~85MB)
            ModelInfo(
                id = "tts-melo-zh-en",
                name = "中文+英文语音合成 (Melo TTS)",
                description = "中英文语音合成模型，约85MB",
                downloadUrl = "$BASE_RELEASE/tts-models/vits-melo-tts-zh_en.tar.bz2",
                sha256 = "e58351ed7149f290a54534538badd4077cdbe6fddc964b24d0bee870415d1514",
                sizeBytes = 160 * 1024 * 1024,
                extractTarget = ttsDir,
                requiredFiles = listOf(
                    "model.onnx",
                    "lexicon.txt",
                    "tokens.txt",
                ),
            ),
        )
    }

    /**
     * Check if a model is fully downloaded and verified.
     */
    fun isModelReady(model: ModelInfo): Boolean {
        return model.requiredFiles.all { fileName ->
            File(model.extractTarget, fileName).exists()
        }
    }

    /**
     * Check available storage space.
     */
    fun getAvailableStorageBytes(): Long {
        val stat = Environment.getExternalStorageDirectory().let { dir ->
            try {
                android.os.StatFs(dir.path)
            } catch (e: Exception) {
                return 0L
            }
        }
        return stat.availableBlocksLong * stat.blockSizeLong
    }

    /**
     * Download a model with progress reporting.
     *
     * This downloads a tar.bz2 archive, extracts it, and verifies required files exist.
     * Note: BZIP2 extraction requires a library; for simplicity, we use the system's
     * tar command via ProcessBuilder on Android (or a pure-Kotlin decompressor).
     */
    fun downloadModel(model: ModelInfo): Flow<DownloadState> = flow {
        emit(DownloadState(model.id, DownloadState.Status.Downloading))

        val cacheFile = File(model.extractTarget.parentFile, "${model.id}.download")
        val extractDir = model.extractTarget

        try {
            // Ensure directory exists
            extractDir.mkdirs()

            // Download with resume support
            var downloaded = if (cacheFile.exists()) cacheFile.length() else 0L

            val connection = URL(model.downloadUrl).openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = 30000
            connection.readTimeout = 60000

            if (downloaded > 0) {
                connection.setRequestProperty("Range", "bytes=$downloaded-")
            }

            val responseCode = connection.responseCode
            val isResuming = responseCode == 206 // Partial Content
            val totalSize = if (isResuming) {
                model.sizeBytes
            } else {
                connection.contentLengthLong.takeIf { it > 0 } ?: model.sizeBytes
            }

            if (responseCode != 200 && responseCode != 206) {
                emit(DownloadState(
                    model.id,
                    DownloadState.Status.Failed,
                    error = "HTTP $responseCode: ${connection.responseMessage}"
                ))
                return@flow
            }

            connection.getInputStream().use { input ->
                FileOutputStream(cacheFile, true).use { output ->
                    val buffer = ByteArray(8192)
                    var bytesRead: Int
                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                        downloaded += bytesRead
                        val progress = if (totalSize > 0) downloaded.toFloat() / totalSize else 0f
                        emit(DownloadState(
                            model.id,
                            DownloadState.Status.Downloading,
                            progress = progress.coerceIn(0f, 1f),
                            downloadedBytes = downloaded,
                            totalBytes = totalSize,
                        ))
                    }
                }
            }

            connection.disconnect()

            // Extract archive
            emit(DownloadState(model.id, DownloadState.Status.Extracting))
            extractArchive(cacheFile, extractDir)

            // Verify
            emit(DownloadState(model.id, DownloadState.Status.Verifying))
            val missingFiles = model.requiredFiles.filter { !File(extractDir, it).exists() }
            if (missingFiles.isNotEmpty()) {
                emit(DownloadState(
                    model.id,
                    DownloadState.Status.Failed,
                    error = "Missing files after extraction: ${missingFiles.joinToString()}"
                ))
                return@flow
            }

            // Clean up cache
            cacheFile.delete()

            // SHA256 verification of the downloaded archive (if hash is provided)
            if (model.sha256.isNotBlank()) {
                emit(DownloadState(model.id, DownloadState.Status.Verifying))
                val actualHash = sha256(cacheFile)
                if (!actualHash.equals(model.sha256, ignoreCase = true)) {
                    Log.e(TAG, "SHA256 mismatch for ${model.id}: expected=${model.sha256}, actual=$actualHash")
                    // Clean up corrupted download
                    cacheFile.delete()
                    emit(DownloadState(
                        model.id,
                        DownloadState.Status.Failed,
                        error = "SHA256 verification failed (downloaded file may be corrupted)"
                    ))
                    return@flow
                }
                Log.i(TAG, "SHA256 verified for ${model.id}: $actualHash")
            }

            // Verify extracted files exist and are non-empty
            val allOk = model.requiredFiles.all { fileName ->
                val file = File(extractDir, fileName)
                file.exists() && file.length() > 0
            }
            if (!allOk) {
                emit(DownloadState(
                    model.id,
                    DownloadState.Status.Failed,
                    error = "Model file verification failed: missing or empty files after extraction"
                ))
                return@flow
            }

            emit(DownloadState(model.id, DownloadState.Status.Complete))
            Log.i(TAG, "Model ${model.id} downloaded and verified successfully")

        } catch (e: Exception) {
            Log.e(TAG, "Failed to download model ${model.id}", e)
            emit(DownloadState(
                model.id,
                DownloadState.Status.Failed,
                error = e.message ?: "Unknown error"
            ))
        }
    }.flowOn(Dispatchers.IO)

    /**
     * Extract a .tar.bz2 archive.
     * Uses Android's built-in tar via ProcessBuilder.
     */
    private suspend fun extractArchive(archive: File, targetDir: File) = withContext(Dispatchers.IO) {
        targetDir.mkdirs()

        // Try using system tar command (available on most Android devices)
        val process = ProcessBuilder(
            "tar", "xjf", archive.absolutePath,
            "-C", targetDir.absolutePath,
            "--strip-components=1"
        ).start()

        val exitCode = process.waitFor()
        if (exitCode != 0) {
            val errorOutput = process.errorStream.bufferedReader().readText()
            throw RuntimeException("tar extraction failed (exit $exitCode): $errorOutput")
        }
    }

    /**
     * Delete a downloaded model.
     */
    suspend fun deleteModel(model: ModelInfo): Boolean = withContext(Dispatchers.IO) {
        try {
            model.requiredFiles.forEach { fileName ->
                File(model.extractTarget, fileName).delete()
            }
            // Also clean up any partial downloads
            File(model.extractTarget.parentFile, "${model.id}.download").delete()
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to delete model ${model.id}", e)
            false
        }
    }

    /** Compute SHA256 of a file. */
    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(8192)
            var read: Int
            while (input.read(buffer).also { read = it } > 0) {
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}

package ai.openclaw.android.skill.builtin

import ai.openclaw.android.model.ImageUtils
import ai.openclaw.android.skill.*
import android.Manifest
import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.hardware.camera2.*
import android.media.ImageReader
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.provider.MediaStore
import android.util.Log
import android.util.Size
import android.view.Surface
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer

/**
 * CameraSkill — 拍照、录制短视频、相册最新照片读取。
 *
 * 使用 Camera2 API 进行拍照/录像，ContentResolver 读取相册。
 * 适用于 minSdk 29 (Android 10)。
 */
class CameraSkill(
    private val context: Context
) : Skill {
    override val id = "camera"
    override val name = "摄像头"
    override val description = "使用设备摄像头拍照、录制短视频、读取相册最新照片"
    override val version = "1.0.0"

    override val instructions = """
# Camera Skill

使用设备摄像头拍照、录制短视频、读取相册最新照片。

## 可用工具
- `capture` — 使用摄像头拍照，返回照片 base64
- `record` — 录制短视频（最大 60 秒），返回文件路径 + 缩略图
- `gallery_latest` — 获取相册中最新的照片

## 权限
- CAMERA — 拍照/录像
- READ_MEDIA_IMAGES (API 33+) / READ_EXTERNAL_STORAGE (API 32-) — 读取相册
""".trimIndent()

    // Camera2 后台线程管理
    private var cameraThread: HandlerThread? = null
    private var cameraHandler: Handler? = null

    override val tools: List<SkillTool> = listOf(
        CaptureTool(),
        RecordTool(),
        GalleryLatestTool()
    )

    // ==================== camera_capture ====================

    private inner class CaptureTool : SkillTool {
        override val name = "capture"
        override val description = "使用设备摄像头拍照。返回照片的 base64 编码。"
        override val parameters = mapOf(
            "camera" to SkillParam(
                type = "string",
                description = "前置或后置摄像头: back/front，默认 back",
                required = false,
                default = "back"
            ),
            "resolution" to SkillParam(
                type = "string",
                description = "分辨率: low(640x480), medium(1280x720), high(1920x1080)，默认 medium",
                required = false,
                default = "medium"
            ),
            "label" to SkillParam(
                type = "string",
                description = "照片描述标签，用于后续检索",
                required = false
            )
        )

        override suspend fun execute(params: Map<String, Any>): SkillResult = withContext(Dispatchers.IO) {
            if (!checkCameraPermission()) {
                return@withContext SkillResult(false, "", "缺少 CAMERA 权限，请在系统设置中授予")
            }

            val cameraFacing = (params["camera"] as? String)?.lowercase() ?: "back"
            val resolution = (params["resolution"] as? String)?.lowercase() ?: "medium"
            val label = params["label"] as? String

            val targetSize = resolveResolution(resolution)

            val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val cameraId = selectCameraId(cameraManager, cameraFacing)
                ?: return@withContext SkillResult(false, "", "未找到 $cameraFacing 摄像头")

            ensureCameraThread()

            val imageReader = ImageReader.newInstance(
                targetSize.width, targetSize.height, ImageFormat.JPEG, 1
            )

            val captureFile = File(context.cacheDir, "camera_${System.currentTimeMillis()}.jpg")

            try {
                val captured = capturePhoto(
                    cameraManager, cameraId, targetSize, imageReader
                )

                if (!captured) {
                    return@withContext SkillResult(false, "", "拍照超时或失败")
                }

                // Read JPEG buffer from ImageReader
                val image = imageReader.acquireNextImage()
                    ?: return@withContext SkillResult(false, "", "无法获取拍摄的图像")

                try {
                    val buffer = image.planes[0].buffer
                    val bytes = ByteArray(buffer.remaining())
                    buffer.get(bytes)

                    // Save to temp file first
                    FileOutputStream(captureFile).use { it.write(bytes) }

                    // Compress if needed
                    val base64Data = compressToBase64(captureFile, targetSize)
                    val width = image.width
                    val height = image.height

                    val labelInfo = if (!label.isNullOrEmpty()) " | 标签: $label" else ""
                    val timestamp = java.text.SimpleDateFormat(
                        "yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US
                    ).format(java.util.Date(System.currentTimeMillis()))

                    val output = buildString {
                        append("📷 拍照成功$labelInfo\n")
                        append("摄像头: $cameraFacing\n")
                        append("分辨率: ${width}x${height}\n")
                        append("时间: $timestamp\n")
                        append("文件大小: ${formatSize(captureFile.length())}\n")
                        append("---\n")
                        append(base64Data)
                    }

                    SkillResult(true, output)
                } finally {
                    image.close()
                }
            } catch (e: SecurityException) {
                SkillResult(false, "", "权限不足: ${e.message}")
            } catch (e: Exception) {
                Log.e("CameraSkill", "capture failed: ${e.message}", e)
                SkillResult(false, "", "拍照失败: ${e.message}")
            } finally {
                imageReader.close()
            }
        }
    }

    // ==================== camera_record ====================

    private inner class RecordTool : SkillTool {
        override val name = "record"
        override val description = "录制短视频。返回视频文件路径和缩略图 base64。"
        override val parameters = mapOf(
            "camera" to SkillParam(
                type = "string",
                description = "前置或后置摄像头: back/front，默认 back",
                required = false,
                default = "back"
            ),
            "duration_seconds" to SkillParam(
                type = "number",
                description = "录制时长（秒），最大 60 秒，默认 10",
                required = false,
                default = 10
            ),
            "resolution" to SkillParam(
                type = "string",
                description = "分辨率: low(640x480), medium(1280x720), high(1920x1080)，默认 medium",
                required = false,
                default = "medium"
            )
        )

        override suspend fun execute(params: Map<String, Any>): SkillResult = withContext(Dispatchers.IO) {
            if (!checkCameraPermission()) {
                return@withContext SkillResult(false, "", "缺少 CAMERA 权限，请在系统设置中授予")
            }

            val cameraFacing = (params["camera"] as? String)?.lowercase() ?: "back"
            val durationSec = (params["duration_seconds"] as? Number)?.toInt()?.coerceIn(1, 60) ?: 10
            val resolution = (params["resolution"] as? String)?.lowercase() ?: "medium"

            val targetSize = resolveResolution(resolution)

            val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val cameraId = selectCameraId(cameraManager, cameraFacing)
                ?: return@withContext SkillResult(false, "", "未找到 $cameraFacing 摄像头")

            // Validate RECORD_AUDIO permission for MediaRecorder
            val hasAudioPermission = ContextCompat.checkSelfPermission(
                context, Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
            if (!hasAudioPermission) {
                return@withContext SkillResult(false, "", "录制视频需要 RECORD_AUDIO 权限")
            }

            val videoFile = File(context.cacheDir, "video_${System.currentTimeMillis()}.mp4")

            try {
                val recorder = MediaRecorder(context).apply {
                    setAudioSource(MediaRecorder.AudioSource.CAMCORDER)
                    setVideoSource(MediaRecorder.VideoSource.SURFACE)
                    setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                    setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                    setVideoEncoder(MediaRecorder.VideoEncoder.H264)
                    setVideoSize(targetSize.width, targetSize.height)
                    setVideoFrameRate(30)
                    setVideoEncodingBitRate(5_000_000)
                    setAudioEncodingBitRate(128_000)
                    setOutputFile(videoFile.absolutePath)
                }

                ensureCameraThread()

                val recorded = recordVideo(
                    cameraManager, cameraId, targetSize, recorder, durationSec
                )

                recorder.release()

                if (!recorded || !videoFile.exists()) {
                    videoFile.delete()
                    return@withContext SkillResult(false, "", "录制失败或超时")
                }

                // Generate thumbnail from the first frame
                val thumbnailBase64 = generateVideoThumbnail(videoFile)

                val output = buildString {
                    append("🎥 录制完成\n")
                    append("摄像头: $cameraFacing\n")
                    append("分辨率: ${targetSize.width}x${targetSize.height}\n")
                    append("时长: ${durationSec}秒\n")
                    append("文件大小: ${formatSize(videoFile.length())}\n")
                    append("路径: ${videoFile.absolutePath}\n")
                    if (thumbnailBase64 != null) {
                        append("---\n")
                        append(thumbnailBase64)
                    }
                }

                SkillResult(true, output)
            } catch (e: Exception) {
                videoFile.delete()
                Log.e("CameraSkill", "record failed: ${e.message}", e)
                SkillResult(false, "", "录制失败: ${e.message}")
            }
        }
    }

    // ==================== camera_gallery_latest ====================

    private inner class GalleryLatestTool : SkillTool {
        override val name = "gallery_latest"
        override val description = "获取相册中最新的照片。"
        override val parameters = mapOf(
            "count" to SkillParam(
                type = "number",
                description = "获取数量，最大 10，默认 1",
                required = false,
                default = 1
            ),
            "max_age_hours" to SkillParam(
                type = "number",
                description = "仅获取最近 N 小时内的照片",
                required = false
            ),
            "thumbnail" to SkillParam(
                type = "boolean",
                description = "是否返回缩略图而非原图，默认 false",
                required = false,
                default = false
            )
        )

        override suspend fun execute(params: Map<String, Any>): SkillResult = withContext(Dispatchers.IO) {
            val hasImagesPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                ContextCompat.checkSelfPermission(
                    context, Manifest.permission.READ_MEDIA_IMAGES
                ) == PackageManager.PERMISSION_GRANTED
            } else {
                ContextCompat.checkSelfPermission(
                    context, Manifest.permission.READ_EXTERNAL_STORAGE
                ) == PackageManager.PERMISSION_GRANTED
            }

            if (!hasImagesPermission) {
                return@withContext SkillResult(
                    false, "",
                    "缺少读取相册权限（READ_MEDIA_IMAGES 或 READ_EXTERNAL_STORAGE）"
                )
            }

            val count = (params["count"] as? Number)?.toInt()?.coerceIn(1, 10) ?: 1
            val maxAgeHours = params["max_age_hours"] as? Number
            val thumbnail = params["thumbnail"] as? Boolean ?: false

            try {
                val cr = context.contentResolver
                val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
                } else {
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                }

                val projection = arrayOf(
                    MediaStore.Images.Media._ID,
                    MediaStore.Images.Media.DISPLAY_NAME,
                    MediaStore.Images.Media.DATE_TAKEN,
                    MediaStore.Images.Media.WIDTH,
                    MediaStore.Images.Media.HEIGHT,
                    MediaStore.Images.Media.SIZE,
                    MediaStore.Images.Media.MIME_TYPE
                )

                val selection = if (maxAgeHours != null) {
                    val cutoffMs = System.currentTimeMillis() - maxAgeHours.toLong() * 3600_000L
                    "${MediaStore.Images.Media.DATE_TAKEN} >= ?"
                } else {
                    null
                }
                val selectionArgs = if (maxAgeHours != null) {
                    arrayOf(System.currentTimeMillis() - maxAgeHours.toLong() * 3600_000L).map { it.toString() }.toTypedArray()
                } else {
                    null
                }

                val sortOrder = "${MediaStore.Images.Media.DATE_TAKEN} DESC LIMIT $count"

                val photos = mutableListOf<String>()
                var foundCount = 0

                cr.query(collection, projection, selection, selectionArgs, sortOrder)?.use { cursor ->
                    val idCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                    val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
                    val dateCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_TAKEN)
                    val widthCol = cursor.getColumnIndex(MediaStore.Images.Media.WIDTH)
                    val heightCol = cursor.getColumnIndex(MediaStore.Images.Media.HEIGHT)
                    val mimeCol = cursor.getColumnIndex(MediaStore.Images.Media.MIME_TYPE)

                    while (cursor.moveToNext() && foundCount < count) {
                        val id = cursor.getLong(idCol)
                        val name = cursor.getString(nameCol) ?: "unknown.jpg"
                        val dateTaken = if (dateCol >= 0) cursor.getLong(dateCol) else 0L
                        val width = if (widthCol >= 0) cursor.getInt(widthCol) else 0
                        val height = if (heightCol >= 0) cursor.getInt(heightCol) else 0
                        val mimeType = if (mimeCol >= 0) { cursor.getString(mimeCol) ?: "image/jpeg" } else { "image/jpeg" }

                        val uri = ContentUris.withAppendedId(collection, id)

                        val base64Data = try {
                            if (thumbnail) {
                                // Load thumbnail via BitmapFactory with sample size
                                val options = android.graphics.BitmapFactory.Options().apply {
                                    inSampleSize = 8
                                }
                                cr.openInputStream(uri)?.use { stream ->
                                    val bitmap = android.graphics.BitmapFactory.decodeStream(stream, null, options)
                                    if (bitmap != null) {
                                        val compressed = ImageUtils.compressBitmap(bitmap, 400)
                                        val content = ImageUtils.bitmapToBase64(compressed)
                                        if (compressed != bitmap) compressed.recycle()
                                        bitmap.recycle()
                                        "data:image/jpeg;base64,${content.base64}"
                                    } else null
                                }
                            } else {
                                // Load full image, compress to max 1200px
                                cr.openInputStream(uri)?.use { stream ->
                                    val bitmap = android.graphics.BitmapFactory.decodeStream(stream)
                                    if (bitmap != null) {
                                        val compressed = ImageUtils.compressBitmap(bitmap)
                                        val content = ImageUtils.bitmapToBase64(compressed)
                                        if (compressed != bitmap) compressed.recycle()
                                        bitmap.recycle()
                                        "data:image/jpeg;base64,${content.base64}"
                                    } else null
                                }
                            }
                        } catch (e: Exception) {
                            Log.w("CameraSkill", "Failed to load image $id: ${e.message}")
                            null
                        }

                        val dateStr = if (dateTaken > 0) {
                            java.text.SimpleDateFormat(
                                "yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US
                            ).format(java.util.Date(dateTaken.toLong()))
                        } else {
                            "unknown"
                        }

                        val photoInfo = buildString {
                            append("📷 $name\n")
                            append("URI: content://media/external/images/media/$id\n")
                            append("拍摄时间: $dateStr\n")
                            append("分辨率: ${width}x${height}\n")
                            append("MIME: $mimeType\n")
                            if (base64Data != null) {
                                append("---\n")
                                append(base64Data)
                            } else {
                                append("(图片加载失败)")
                            }
                        }

                        photos.add(photoInfo)
                        foundCount++
                    }
                }

                if (photos.isEmpty()) {
                    return@withContext SkillResult(true, "相册中没有符合条件的照片")
                }

                val output = buildString {
                    append("📸 相册最新照片 (找到 ${photos.size} 张)\n")
                    append("---\n")
                    photos.forEach { photo ->
                        append(photo)
                        append("\n---\n")
                    }
                }

                SkillResult(true, output)
            } catch (e: SecurityException) {
                SkillResult(false, "", "权限不足: ${e.message}")
            } catch (e: Exception) {
                Log.e("CameraSkill", "gallery_latest failed: ${e.message}", e)
                SkillResult(false, "", "读取相册失败: ${e.message}")
            }
        }
    }

    // ==================== Camera2 helper methods ====================

    /**
     * Ensure the background camera thread is running.
     */
    private fun ensureCameraThread() {
        if (cameraThread == null) {
            cameraThread = HandlerThread("CameraSkill-Thread").also { it.start() }
            cameraHandler = Handler(cameraThread!!.looper)
        }
    }

    /**
     * Select a camera ID by facing direction.
     */
    private fun selectCameraId(
        cameraManager: CameraManager,
        facing: String
    ): String? {
        val desiredFacing = if (facing == "front") {
            CameraCharacteristics.LENS_FACING_FRONT
        } else {
            CameraCharacteristics.LENS_FACING_BACK
        }

        for (id in cameraManager.cameraIdList) {
            val chars = cameraManager.getCameraCharacteristics(id)
            val lensFacing = chars.get(CameraCharacteristics.LENS_FACING)
            if (lensFacing == desiredFacing) {
                return id
            }
        }

        // Fallback: return first available camera
        return cameraManager.cameraIdList.firstOrNull()
    }

    /**
     * Resolve resolution string to a Size.
     */
    private fun resolveResolution(resolution: String): Size = when (resolution) {
        "low" -> Size(640, 480)
        "high" -> Size(1920, 1080)
        else -> Size(1280, 720) // medium
    }

    /**
     * Capture a photo using Camera2 API.
     * Returns true if capture succeeded.
     */
    private fun capturePhoto(
        cameraManager: CameraManager,
        cameraId: String,
        targetSize: Size,
        imageReader: ImageReader
    ): Boolean {
        val latch = java.util.concurrent.CountDownLatch(1)
        var success = false

        val stateCallback = object : CameraDevice.StateCallback() {
            override fun onOpened(device: CameraDevice) {
                try {
                    val captureRequest = device.createCaptureRequest(
                        CameraDevice.TEMPLATE_STILL_CAPTURE
                    ).apply {
                        addTarget(imageReader.surface)
                    }

                    device.createCaptureSession(
                        listOf(imageReader.surface),
                        object : CameraCaptureSession.StateCallback() {
                            override fun onConfigured(session: CameraCaptureSession) {
                                try {
                                    val request = captureRequest.build()
                                    session.capture(request, null, cameraHandler)
                                    // Wait for image available callback
                                    imageReader.setOnImageAvailableListener({
                                        success = true
                                        latch.countDown()
                                    }, cameraHandler)
                                } catch (e: Exception) {
                                    Log.e("CameraSkill", "Capture request failed: ${e.message}")
                                    latch.countDown()
                                }
                            }

                            override fun onConfigureFailed(session: CameraCaptureSession) {
                                Log.e("CameraSkill", "Capture session configure failed")
                                latch.countDown()
                            }
                        },
                        cameraHandler
                    )
                } catch (e: Exception) {
                    Log.e("CameraSkill", "Create capture request failed: ${e.message}")
                    latch.countDown()
                }
            }

            override fun onDisconnected(device: CameraDevice) {
                device.close()
                latch.countDown()
            }

            override fun onError(device: CameraDevice, error: Int) {
                Log.e("CameraSkill", "Camera error: $error")
                device.close()
                latch.countDown()
            }
        }

        try {
            cameraManager.openCamera(cameraId, stateCallback, cameraHandler)
            // Wait up to 10 seconds for capture
            latch.await(10, java.util.concurrent.TimeUnit.SECONDS)
        } catch (e: Exception) {
            Log.e("CameraSkill", "openCamera failed: ${e.message}")
        }

        return success
    }

    /**
     * Record a video using Camera2 + MediaRecorder.
     */
    private fun recordVideo(
        cameraManager: CameraManager,
        cameraId: String,
        targetSize: Size,
        recorder: MediaRecorder,
        durationSec: Int
    ): Boolean {
        var success = false
        val latch = java.util.concurrent.CountDownLatch(1)

        // Prepare MediaRecorder
        recorder.prepare()

        val stateCallback = object : CameraDevice.StateCallback() {
            override fun onOpened(device: CameraDevice) {
                try {
                    val surface = recorder.surface
                    val captureRequest = device.createCaptureRequest(
                        CameraDevice.TEMPLATE_RECORD
                    ).apply {
                        addTarget(surface)
                    }

                    device.createCaptureSession(
                        listOf(surface),
                        object : CameraCaptureSession.StateCallback() {
                            override fun onConfigured(session: CameraCaptureSession) {
                                try {
                                    recorder.start()
                                    val request = captureRequest.build()
                                    session.setRepeatingRequest(request, null, cameraHandler)

                                    // Stop after duration
                                    cameraHandler?.postDelayed({
                                        try {
                                            session.stopRepeating()
                                            recorder.stop()
                                            success = true
                                        } catch (e: Exception) {
                                            Log.e("CameraSkill", "Stop recording failed: ${e.message}")
                                        } finally {
                                            device.close()
                                            latch.countDown()
                                        }
                                    }, durationSec * 1000L)
                                } catch (e: Exception) {
                                    Log.e("CameraSkill", "Start recording failed: ${e.message}")
                                    device.close()
                                    latch.countDown()
                                }
                            }

                            override fun onConfigureFailed(session: CameraCaptureSession) {
                                Log.e("CameraSkill", "Record session configure failed")
                                device.close()
                                latch.countDown()
                            }
                        },
                        cameraHandler
                    )
                } catch (e: Exception) {
                    Log.e("CameraSkill", "Create record request failed: ${e.message}")
                    latch.countDown()
                }
            }

            override fun onDisconnected(device: CameraDevice) {
                device.close()
                latch.countDown()
            }

            override fun onError(device: CameraDevice, error: Int) {
                Log.e("CameraSkill", "Camera error: $error")
                device.close()
                latch.countDown()
            }
        }

        try {
            cameraManager.openCamera(cameraId, stateCallback, cameraHandler)
            latch.await((durationSec + 5).toLong(), java.util.concurrent.TimeUnit.SECONDS)
        } catch (e: Exception) {
            Log.e("CameraSkill", "openCamera for record failed: ${e.message}")
        }

        return success
    }

    /**
     * Generate a thumbnail from a video file by extracting the first frame.
     */
    private fun generateVideoThumbnail(videoFile: File): String? {
        return try {
            val retriever = android.media.MediaMetadataRetriever()
            retriever.setDataSource(videoFile.absolutePath)
            val frame = retriever.getFrameAtTime(0) // first frame
            retriever.release()

            if (frame != null) {
                val compressed = ImageUtils.compressBitmap(frame, 400)
                val content = ImageUtils.bitmapToBase64(compressed)
                if (compressed != frame) compressed.recycle()
                frame.recycle()
                "data:image/jpeg;base64,${content.base64}"
            } else {
                null
            }
        } catch (e: Exception) {
            Log.w("CameraSkill", "Thumbnail generation failed: ${e.message}")
            null
        }
    }

    /**
     * Compress image file to base64 with size limit.
     */
    private fun compressToBase64(file: File, targetSize: Size): String {
        return try {
            val bitmap = android.graphics.BitmapFactory.decodeFile(file.absolutePath)
                ?: throw IllegalStateException("Failed to decode captured image")

            val compressed = ImageUtils.compressBitmap(bitmap)
            val content = ImageUtils.bitmapToBase64(compressed)
            if (compressed != bitmap) compressed.recycle()
            bitmap.recycle()
            "data:image/jpeg;base64,${content.base64}"
        } catch (e: Exception) {
            // Fallback: read raw file as base64
            file.readBytes().let { bytes ->
                val encoded = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
                "data:image/jpeg;base64,$encoded"
            }
        }
    }

    // ==================== Permission check ====================

    private fun checkCameraPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            context, Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
    }

    // ==================== Utility methods ====================

    private fun formatSize(bytes: Long): String = when {
        bytes < 1024 -> "${bytes}B"
        bytes < 1024 * 1024 -> "${bytes / 1024}KB"
        bytes < 1024L * 1024 * 1024 -> String.format("%.1fMB", bytes / (1024.0 * 1024.0))
        else -> String.format("%.1fGB", bytes / (1024.0 * 1024.0 * 1024.0))
    }

    // ==================== Skill lifecycle ====================

    override fun initialize(context: SkillContext) {
        Log.i("CameraSkill", "Initialized")
    }

    override fun cleanup() {
        cameraHandler?.removeCallbacksAndMessages(null)
        cameraThread?.quitSafely()
        cameraThread = null
        cameraHandler = null
    }
}

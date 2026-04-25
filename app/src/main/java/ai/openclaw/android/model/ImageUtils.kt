package ai.openclaw.android.model

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import androidx.core.content.FileProvider
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * 图片处理工具类 - 用于多模态消息的图片压缩和编码
 */
object ImageUtils {
    
    private const val MAX_DIMENSION = 1200      // 最大边长
    private const val JPEG_QUALITY = 80          // JPEG 压缩质量
    private const val MAX_BASE64_SIZE = 4 * 1024 * 1024  // 4MB Base64 上限
    private const val MAX_IMAGES_PER_MESSAGE = 3 // 单条消息最多图片数
    
    /**
     * 将 URI 指向的图片转换为 ImageContent
     * 自动压缩至 MAX_DIMENSION，编码为 Base64
     */
    fun uriToBase64(context: Context, uri: Uri, maxSize: Int = MAX_DIMENSION): ImageContent? {
        return try {
            val inputStream = context.contentResolver.openInputStream(uri) ?: return null
            val bitmap = BitmapFactory.decodeStream(inputStream)
            inputStream.close()
            
            if (bitmap == null) return null
            
            val mediaType = context.contentResolver.getType(uri) ?: "image/jpeg"
            val compressed = compressBitmap(bitmap, maxSize)
            val result = bitmapToBase64(compressed, mediaType = mediaType)
            
            if (compressed != bitmap) compressed.recycle()
            bitmap.recycle()
            
            result
        } catch (e: Exception) {
            null
        }
    }
    
    /**
     * 将 Bitmap 转换为 Base64 编码的 ImageContent
     */
    fun bitmapToBase64(
        bitmap: Bitmap, 
        quality: Int = JPEG_QUALITY,
        mediaType: String = "image/jpeg"
    ): ImageContent {
        val outputStream = ByteArrayOutputStream()
        val format = if (mediaType.contains("png")) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG
        bitmap.compress(format, quality, outputStream)
        val base64 = Base64.encodeToString(outputStream.toByteArray(), Base64.NO_WRAP)
        return ImageContent(
            base64 = base64,
            mediaType = if (format == Bitmap.CompressFormat.PNG) "image/png" else "image/jpeg"
        )
    }
    
    /**
     * 压缩 Bitmap 至指定最大边长，保持宽高比
     */
    fun compressBitmap(bitmap: Bitmap, maxDimension: Int = MAX_DIMENSION): Bitmap {
        val width = bitmap.width
        val height = bitmap.height
        
        if (width <= maxDimension && height <= maxDimension) return bitmap
        
        val ratio = maxDimension.toFloat() / maxOf(width, height).toFloat()
        val newWidth = (width * ratio).toInt()
        val newHeight = (height * ratio).toInt()
        
        return Bitmap.createScaledBitmap(bitmap, newWidth, newHeight, true)
    }
    
    /**
     * 验证图片列表是否合法（数量限制、大小限制）
     */
    fun validateImages(images: List<ImageContent>): List<ImageContent> {
        return images.take(MAX_IMAGES_PER_MESSAGE).filter { 
            it.base64.length <= MAX_BASE64_SIZE 
        }
    }
    
    /**
     * 检查 Message 是否包含图片
     */
    fun hasImages(message: Message): Boolean {
        return !message.images.isNullOrEmpty()
    }

    /**
     * 将拍照返回的 Bitmap 保存到临时文件并返回 content URI
     * 用于 ActivityResultContracts.TakePicturePreview 的回调
     */
    fun saveBitmapToTempUri(context: Context, bitmap: Bitmap): Uri? {
        return try {
            val file = File(context.cacheDir, "camera_${System.currentTimeMillis()}.jpg")
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 85, it) }
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        } catch (e: Exception) {
            null
        }
    }
}

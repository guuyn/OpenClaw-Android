package ai.openclaw.android.model

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * ImageUtils tests using Robolectric for Android Context and Bitmap APIs.
 *
 * - compressBitmap: scales to fit maxDimension while preserving aspect ratio
 * - bitmapToBase64: encodes bitmap to Base64 + JPEG/PNG media type
 * - validateImages: caps at 3 images, filters out oversized base64
 * - saveBitmapToTempUri: writes a JPEG to cache and returns a FileProvider URI
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [35])
class ImageUtilsTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
    }

    /** Create a solid-color Bitmap of the given dimensions for testing. */
    private fun solidBitmap(width: Int, height: Int, color: Int = Color.RED): Bitmap {
        return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply {
            eraseColor(color)
        }
    }

    // ==================== compressBitmap ====================

    @Test
    fun `compressBitmap returns same bitmap when under limit`() {
        val small = solidBitmap(100, 100)
        val result = ImageUtils.compressBitmap(small, maxDimension = 1200)
        assertSame(small, result)
        assertEquals(100, result.width)
        assertEquals(100, result.height)
    }

    @Test
    fun `compressBitmap scales down oversized width`() {
        val large = solidBitmap(2400, 1000)
        val result = ImageUtils.compressBitmap(large, maxDimension = 1200)
        assertEquals(1200, result.width)
        assertEquals(500, result.height) // aspect ratio preserved (1000/2400 * 1200 = 500)
    }

    @Test
    fun `compressBitmap scales down oversized height`() {
        val tall = solidBitmap(800, 2400)
        val result = ImageUtils.compressBitmap(tall, maxDimension = 1200)
        assertEquals(400, result.width)  // 800/2400 * 1200 = 400
        assertEquals(1200, result.height)
    }

    @Test
    fun `compressBitmap returns same bitmap when exactly at limit`() {
        val exact = solidBitmap(1200, 1200)
        val result = ImageUtils.compressBitmap(exact, maxDimension = 1200)
        assertSame(exact, result)
    }

    @Test
    fun `compressBitmap scales to custom max dimension`() {
        val huge = solidBitmap(3000, 2000)
        val result = ImageUtils.compressBitmap(huge, maxDimension = 600)
        assertEquals(600, result.width)
        assertEquals(400, result.height)
    }

    @Test
    fun `compressBitmap handles square aspect ratio`() {
        val sq = solidBitmap(4000, 4000)
        val result = ImageUtils.compressBitmap(sq, maxDimension = 1000)
        assertEquals(1000, result.width)
        assertEquals(1000, result.height)
    }

    // ==================== bitmapToBase64 ====================

    @Test
    fun `bitmapToBase64 produces non-empty base64`() {
        val bmp = solidBitmap(50, 50)
        val imageContent = ImageUtils.bitmapToBase64(bmp)
        assertTrue(imageContent.base64.isNotEmpty())
        assertEquals("image/jpeg", imageContent.mediaType)
    }

    @Test
    fun `bitmapToBase64 round trip decodes back to image bytes`() {
        val bmp = solidBitmap(100, 100, Color.BLUE)
        val imageContent = ImageUtils.bitmapToBase64(bmp, quality = 80)
        val decodedBytes = android.util.Base64.decode(imageContent.base64, android.util.Base64.NO_WRAP)
        assertTrue("Decoded bytes should be non-empty", decodedBytes.isNotEmpty())
        // The bytes should form a valid JPEG (starts with FF D8 FF)
        assertEquals(0xFF.toByte(), decodedBytes[0])
        assertEquals(0xD8.toByte(), decodedBytes[1])
        assertEquals(0xFF.toByte(), decodedBytes[2])
    }

    @Test
    fun `bitmapToBase64 with png media type produces png bytes`() {
        val bmp = solidBitmap(50, 50)
        val imageContent = ImageUtils.bitmapToBase64(bmp, mediaType = "image/png")
        assertEquals("image/png", imageContent.mediaType)
        val decodedBytes = android.util.Base64.decode(imageContent.base64, android.util.Base64.NO_WRAP)
        // PNG magic number: 89 50 4E 47
        assertEquals(0x89.toByte(), decodedBytes[0])
        assertEquals(0x50.toByte(), decodedBytes[1]) // P
        assertEquals(0x4E.toByte(), decodedBytes[2]) // N
        assertEquals(0x47.toByte(), decodedBytes[3]) // G
    }

    @Test
    fun `bitmapToBase64 with higher quality produces larger output`() {
        val bmp = solidBitmap(200, 200, color = Color.argb(255, 100, 50, 200))
        val lowQ = ImageUtils.bitmapToBase64(bmp, quality = 10)
        val highQ = ImageUtils.bitmapToBase64(bmp, quality = 100)
        assertTrue(
            "Higher quality should produce larger output: high=${highQ.base64.length}, low=${lowQ.base64.length}",
            highQ.base64.length > lowQ.base64.length
        )
    }

    // ==================== validateImages ====================

    @Test
    fun `validateImages accepts empty list`() {
        val result = ImageUtils.validateImages(emptyList())
        assertEquals(0, result.size)
    }

    @Test
    fun `validateImages caps at max 3 images`() {
        val images = (1..5).map {
            ImageContent(base64 = "img$it", mediaType = "image/jpeg")
        }
        val result = ImageUtils.validateImages(images)
        assertEquals(3, result.size)
        assertEquals("img1", result[0].base64)
        assertEquals("img2", result[1].base64)
        assertEquals("img3", result[2].base64)
    }

    @Test
    fun `validateImages keeps all when under limit`() {
        val images = listOf(
            ImageContent(base64 = "a"),
            ImageContent(base64 = "b")
        )
        val result = ImageUtils.validateImages(images)
        assertEquals(2, result.size)
    }

    @Test
    fun `validateImages filters oversized base64`() {
        val small = ImageContent(base64 = "small")
        val huge = ImageContent(base64 = "x".repeat(5 * 1024 * 1024)) // 5MB > 4MB limit
        val result = ImageUtils.validateImages(listOf(small, huge))
        assertEquals(1, result.size)
        assertEquals("small", result[0].base64)
    }

    @Test
    fun `validateImages preserves order up to limit`() {
        val images = (1..3).map {
            ImageContent(base64 = "img$it", description = "desc $it")
        }
        val result = ImageUtils.validateImages(images)
        assertEquals(3, result.size)
        for (i in 0..2) {
            assertEquals("img${i + 1}", result[i].base64)
            assertEquals("desc ${i + 1}", result[i].description)
        }
    }

    // ==================== saveBitmapToTempUri ====================

    @Test
    fun `saveBitmapToTempUri writes JPEG to cacheDir`() {
        val bmp = solidBitmap(100, 100)
        ImageUtils.saveBitmapToTempUri(context, bmp)

        // The function writes a JPEG file into context.cacheDir with prefix "camera_"
        val cacheFiles = context.cacheDir.listFiles { f -> f.name.startsWith("camera_") && f.name.endsWith(".jpg") }
        assertNotNull("cacheDir should list files", cacheFiles)
        assertTrue("At least one camera_*.jpg file should exist", cacheFiles!!.isNotEmpty())

        val bytes = cacheFiles.first().readBytes()
        assertEquals(0xFF.toByte(), bytes[0])
        assertEquals(0xD8.toByte(), bytes[1])
        assertEquals(0xFF.toByte(), bytes[2])
    }

    @Test
    fun `saveBitmapToTempUri produces unique filenames for multiple calls`() {
        // Each call should write a new file (timestamp differs)
        val cacheBefore = context.cacheDir.listFiles { f -> f.name.startsWith("camera_") }?.size ?: 0
        ImageUtils.saveBitmapToTempUri(context, solidBitmap(50, 50))
        Thread.sleep(2)
        ImageUtils.saveBitmapToTempUri(context, solidBitmap(50, 50))
        Thread.sleep(2)
        ImageUtils.saveBitmapToTempUri(context, solidBitmap(50, 50))

        val cacheAfter = context.cacheDir.listFiles { f -> f.name.startsWith("camera_") }?.size ?: 0
        assertEquals(3, cacheAfter - cacheBefore)
    }

    // ==================== uriToBase64 (with mock URI) ====================

    @Test
    fun `uriToBase64 with file URI returns ImageContent`() {
        // Robolectric's contentResolver is lenient and may not throw on content:// URIs.
        // We instead verify the positive path: a real file URI under cacheDir decodes correctly.
        val bmp = solidBitmap(50, 50, Color.GREEN)
        val tmpFile = java.io.File(context.cacheDir, "test_image.jpg")
        tmpFile.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        val fileUri = Uri.fromFile(tmpFile)
        val result = ImageUtils.uriToBase64(context, fileUri)
        assertNotNull("Valid file URI should produce ImageContent", result)
        assertEquals("image/jpeg", result!!.mediaType)
        assertTrue(result.base64.isNotEmpty())
        tmpFile.delete()
    }

    @Test
    fun `hasImages returns true when message contains images`() {
        val msg = Message(
            role = "user",
            content = "what's this?",
            images = listOf(ImageContent(base64 = "img"))
        )
        assertTrue(ImageUtils.hasImages(msg))
    }

    @Test
    fun `hasImages returns false when message has no images`() {
        val msg = Message(role = "user", content = "hello")
        assertFalse(ImageUtils.hasImages(msg))
    }

    @Test
    fun `hasImages returns false when images list is empty`() {
        val msg = Message(role = "user", content = "hi", images = emptyList())
        assertFalse(ImageUtils.hasImages(msg))
    }
}
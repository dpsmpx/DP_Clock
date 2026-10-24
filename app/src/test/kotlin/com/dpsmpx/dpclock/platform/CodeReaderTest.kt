package com.dpsmpx.dpclock.platform

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import org.junit.Assert.assertEquals
import org.junit.Test

class CodeReaderTest {
    /** QR-код как кадр камеры: тёмные модули на светлом поле, строки длиннее ширины. */
    private fun frame(text: String, size: Int = 240, stride: Int = 256): ByteArray {
        val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, mapOf(EncodeHintType.CHARACTER_SET to "UTF-8"))
        return ByteArray(stride * size) { i ->
            val x = i % stride
            val y = i / stride
            if (x < size && matrix[x, y]) 20 else 230.toByte()
        }
    }

    @Test
    fun readsAQrCodeFromALumaFrame() {
        val reader = CodeReader()
        assertEquals("DP Clock: кухня", reader.read(frame("DP Clock: кухня"), 240, 240, rowStride = 256))
        // Тот же распознаватель — и для следующего кадра.
        assertEquals("ванная", reader.read(frame("ванная"), 240, 240, rowStride = 256))
    }

    @Test
    fun emptyFrameHasNoCode() {
        assertEquals(null, CodeReader().read(ByteArray(240 * 240) { 128.toByte() }, 240, 240))
    }
}

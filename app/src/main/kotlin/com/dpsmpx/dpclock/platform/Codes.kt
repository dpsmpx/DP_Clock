package com.dpsmpx.dpclock.platform

import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer

/**
 * Распознавание QR-кода или штрихкода в кадре камеры (D-015, п. 3) — ZXing.
 * Не потокобезопасен: один распознаватель на поток анализа кадров.
 */
class CodeReader {
    private val reader = MultiFormatReader().apply {
        setHints(
            mapOf(
                DecodeHintType.POSSIBLE_FORMATS to listOf(
                    BarcodeFormat.QR_CODE,
                    BarcodeFormat.DATA_MATRIX,
                    BarcodeFormat.AZTEC,
                    BarcodeFormat.EAN_13,
                    BarcodeFormat.EAN_8,
                    BarcodeFormat.UPC_A,
                    BarcodeFormat.CODE_128,
                ),
                DecodeHintType.TRY_HARDER to true,
            ),
        )
    }

    /** Текст кода в кадре яркости [luma] (строки по [rowStride] байт); null — кода нет. */
    fun read(luma: ByteArray, width: Int, height: Int, rowStride: Int = width): String? = try {
        val source = PlanarYUVLuminanceSource(luma, rowStride, height, 0, 0, width, height, false)
        reader.decodeWithState(BinaryBitmap(HybridBinarizer(source))).text
    } catch (_: Exception) {
        // ZXing сообщает «кода нет» исключением; для кадра камеры это обычный случай.
        null
    } finally {
        reader.reset()
    }
}

package com.dpsmpx.dpclock.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.dpsmpx.dpclock.model.PhotoSignature
import com.dpsmpx.dpclock.platform.CodeReader
import java.util.concurrent.Executors

/** Кадр камеры, сведённый к нужному: код или подпись снимка (D-015, п. 3). */
sealed interface CameraResult {
    data class Code(val text: String) : CameraResult

    class Signature(val value: IntArray) : CameraResult
}

/** Что искать в кадре. */
enum class CameraMode { CODE, PHOTO }

fun Context.hasCamera(): Boolean = checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

/**
 * Видоискатель задней камеры. Кадры разбираются в памяти, в отдельном потоке:
 * в режиме [CameraMode.CODE] — [onResult] на каждый найденный код, в режиме
 * [CameraMode.PHOTO] — на каждый кадр его подпись. Ни кадры, ни снимки не
 * сохраняются (D-011, п. 8). Камера не открылась — [onError].
 */
@Composable
fun CameraScanner(mode: CameraMode, onResult: (CameraResult) -> Unit, onError: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val result by rememberUpdatedState(onResult)
    val error by rememberUpdatedState(onError)
    val previewView = remember { PreviewView(context) }

    DisposableEffect(lifecycleOwner, mode) {
        val main = ContextCompat.getMainExecutor(context)
        val analyzer = Executors.newSingleThreadExecutor()
        val reader = CodeReader()
        val future = ProcessCameraProvider.getInstance(context)
        var provider: ProcessCameraProvider? = null
        future.addListener({
            val cameras = runCatching { future.get() }.getOrNull()
            if (cameras == null) {
                error()
                return@addListener
            }
            provider = cameras
            val preview = Preview.Builder().build().also { it.surfaceProvider = previewView.surfaceProvider }
            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also { useCase ->
                    useCase.setAnalyzer(analyzer) { image ->
                        val found = image.use { frame -> analyze(frame, mode, reader) }
                        if (found != null) main.execute { result(found) }
                    }
                }
            runCatching {
                cameras.unbindAll()
                cameras.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
            }.onFailure { error() }
        }, main)
        onDispose {
            provider?.unbindAll()
            analyzer.shutdown()
        }
    }
    AndroidView(factory = { previewView }, modifier = modifier)
}

/** Яркость кадра — первая плоскость YUV; строки могут быть длиннее ширины. */
private fun analyze(frame: ImageProxy, mode: CameraMode, reader: CodeReader): CameraResult? {
    val plane = frame.planes.firstOrNull() ?: return null
    val buffer = plane.buffer
    val luma = ByteArray(buffer.remaining()).also { buffer.get(it) }
    return when (mode) {
        CameraMode.CODE -> reader.read(luma, frame.width, frame.height, plane.rowStride)?.let { CameraResult.Code(it) }
        CameraMode.PHOTO -> CameraResult.Signature(PhotoSignature.of(luma, frame.width, frame.height, plane.rowStride))
    }
}

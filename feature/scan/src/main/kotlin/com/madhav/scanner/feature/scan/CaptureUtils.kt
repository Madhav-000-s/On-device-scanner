package com.madhav.scanner.feature.scan

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.Executor
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** CameraX's default in-memory capture format is JPEG — a single plane of encoded bytes. */
suspend fun ImageCapture.captureBitmap(executor: Executor): Bitmap = suspendCancellableCoroutine { continuation ->
    takePicture(
        executor,
        object : ImageCapture.OnImageCapturedCallback() {
            override fun onCaptureSuccess(image: ImageProxy) {
                try {
                    continuation.resume(image.toJpegBitmap())
                } catch (t: Throwable) {
                    continuation.resumeWithException(t)
                } finally {
                    image.close()
                }
            }

            override fun onError(exception: ImageCaptureException) {
                continuation.resumeWithException(exception)
            }
        },
    )
}

private fun ImageProxy.toJpegBitmap(): Bitmap {
    val buffer = planes[0].buffer
    val bytes = ByteArray(buffer.remaining())
    buffer.get(bytes)
    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        ?: error("failed to decode captured JPEG")
}

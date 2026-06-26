package com.madhav.scanner.core.ocr

import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * ML Kit bundled text recognition (DESIGN.md §7.2): the bundled model
 * (`com.google.mlkit:text-recognition`), not the Play-Services-downloaded variant
 * (`com.google.android.gms:play-services-mlkit-text-recognition`) -- a few extra MB of APK
 * buys removing a first-run network dependency and a whole error path, which matters for a
 * scanner that must work offline in a restaurant basement.
 */
class MlKitRecognizer : AutoCloseable {

    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    suspend fun recognize(bitmap: Bitmap): List<RecognizedElement> {
        val image = InputImage.fromBitmap(bitmap, 0)
        val text = suspendCancellableCoroutine { continuation ->
            recognizer.process(image)
                .addOnSuccessListener { continuation.resume(it) }
                .addOnFailureListener { continuation.resumeWithException(it) }
        }

        return text.textBlocks.flatMap { block ->
            block.lines.flatMap { line ->
                line.elements.mapNotNull { element ->
                    val box = element.boundingBox ?: return@mapNotNull null
                    RecognizedElement(
                        text = element.text,
                        left = box.left.toFloat(),
                        top = box.top.toFloat(),
                        right = box.right.toFloat(),
                        bottom = box.bottom.toFloat(),
                    )
                }
            }
        }
    }

    override fun close() {
        recognizer.close()
    }
}

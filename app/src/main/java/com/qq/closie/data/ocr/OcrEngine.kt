package com.qq.closie.data.ocr

import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.CancellationException

/**
 * On-device OCR wrapper around ML Kit Text Recognition (Chinese + Latin). No text or image is
 * ever uploaded: recognition runs entirely on the device. Shared by floating quick-capture and
 * the measurement (size-chart) importer.
 */
object OcrEngine {

    /** Recognizes text locally; coroutine cancellation remains cancellation. */
    suspend fun recognizeText(bitmap: Bitmap, releaseAfterRecognition: Boolean = false): Result<String> =
        try {
            Result.success(recognizeInternal(bitmap, releaseAfterRecognition))
        } catch (e: CancellationException) {throw e
        } catch (e: Exception) {
            Result.failure(e)
        }

    private suspend fun recognizeInternal(bitmap: Bitmap, releaseAfterRecognition: Boolean): String = suspendCancellableCoroutine { cont ->
        val recognizer = TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
        try {
            val image = InputImage.fromBitmap(bitmap, 0)
            recognizer.process(image)
                .addOnSuccessListener { visionText ->
                    if (cont.isActive) cont.resumeWith(Result.success(visionText.text))
                }
                .addOnFailureListener { e ->
                    if (cont.isActive) cont.resumeWith(Result.failure(e))
                }
                .addOnCompleteListener { runCatching { recognizer.close() }; if (releaseAfterRecognition) bitmap.recycle() }
        } catch (e: Exception) {
            runCatching { recognizer.close() }
            if (releaseAfterRecognition) bitmap.recycle()
            if (cont.isActive) cont.resumeWith(Result.failure(e))
        }
    }
}

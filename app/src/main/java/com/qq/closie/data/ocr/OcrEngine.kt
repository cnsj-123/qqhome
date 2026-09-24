package com.qq.closie.data.ocr

import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * On-device OCR wrapper around ML Kit Text Recognition (Chinese + Latin). No text or image is
 * ever uploaded: recognition runs entirely on the device. Shared by floating quick-capture and
 * the measurement (size-chart) importer.
 */
object OcrEngine {

    /** Recognizes all text in [bitmap] and returns the concatenated string, never throwing. */
    suspend fun recognizeText(bitmap: Bitmap): Result<String> =
        try {
            Result.success(recognizeInternal(bitmap))
        } catch (e: Exception) {
            Result.failure(e)
        }

    private suspend fun recognizeInternal(bitmap: Bitmap): String = suspendCancellableCoroutine { cont ->
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
                .addOnCompleteListener { runCatching { recognizer.close() } }
        } catch (e: Exception) {
            runCatching { recognizer.close() }
            if (cont.isActive) cont.resumeWith(Result.failure(e))
        }
    }
}

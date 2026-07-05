package com.project.smartattendance

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import androidx.core.graphics.scale
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import kotlinx.coroutines.tasks.await
import org.json.JSONArray
import kotlin.math.sqrt

private const val FACE_TEMPLATE_SIZE = 24
private const val FACE_MATCH_THRESHOLD = 0.40f

data class FaceTemplateResult(
    val success: Boolean,
    val message: String,
    val template: String? = null
)

data class FaceMatchResult(
    val success: Boolean,
    val message: String,
    val similarity: Float = 0f
)

suspend fun createFaceTemplate(bitmap: Bitmap): FaceTemplateResult {
    val face = detectPrimaryFace(bitmap)
        ?: return FaceTemplateResult(false, "No clear face was detected. Capture again in better lighting.")

    val cropped = cropFaceBitmap(bitmap, face)
        ?: return FaceTemplateResult(false, "Failed to isolate face area.")

    val features = extractFaceFeatures(cropped)
        ?: return FaceTemplateResult(false, "The detected face could not be processed. Try again.")

    return FaceTemplateResult(
        success = true,
        message = "Face enrolled successfully.",
        template = serializeFaceTemplate(features)
    )
}

suspend fun verifyFaceWithModel(
    context: Context,
    enrolledBitmap: Bitmap,
    liveBitmap: Bitmap
): FaceMatchResult {
    val enrolledFace = detectPrimaryFace(enrolledBitmap)
        ?: return FaceMatchResult(false, "The enrolled face image is not usable. Enroll again.")
    val liveFace = detectPrimaryFace(liveBitmap)
        ?: return FaceMatchResult(false, "No face was detected in the captured snapshot.")

    val enrolledCrop = cropFaceBitmap(enrolledBitmap, enrolledFace)
        ?: return FaceMatchResult(false, "The enrolled face could not be prepared for verification.")
    val liveCrop = cropFaceBitmap(liveBitmap, liveFace)
        ?: return FaceMatchResult(false, "The live face could not be prepared for verification.")

    val enrolledFeatures = extractFaceFeatures(enrolledCrop)
        ?: return FaceMatchResult(false, "The enrolled face image could not be processed.")
    val liveFeatures = extractFaceFeatures(liveCrop)
        ?: return FaceMatchResult(false, "The live face image could not be processed.")

    val similarity = cosineSimilarity(enrolledFeatures, liveFeatures)
    val success = similarity >= FACE_MATCH_THRESHOLD
    val percentage = (similarity * 100f).toInt().coerceAtMost(100)

    return if (success) {
        FaceMatchResult(true, "Face matched with $percentage% confidence.", similarity)
    } else {
        FaceMatchResult(false, "Face did not match the enrolled student profile ($percentage%).", similarity)
    }
}

fun decodeBase64Bitmap(value: String): Bitmap? {
    return runCatching {
        val bytes = android.util.Base64.decode(value, android.util.Base64.DEFAULT)
        android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    }.getOrNull()
}

private suspend fun detectPrimaryFace(bitmap: Bitmap): Face? {
    val detector = FaceDetection.getClient(
        FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
            .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_NONE)
            .setContourMode(FaceDetectorOptions.CONTOUR_MODE_NONE)
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_NONE)
            .setMinFaceSize(0.15f)
            .build()
    )

    return try {
        val image = InputImage.fromBitmap(bitmap, 0)
        detector.process(image).await().maxByOrNull { face ->
            face.boundingBox.width() * face.boundingBox.height()
        }
    } catch (e: Exception) {
        null
    } finally {
        detector.close()
    }
}

private fun extractFaceFeatures(faceBitmap: Bitmap): FloatArray? {
    val scaled = faceBitmap.scale(FACE_TEMPLATE_SIZE, FACE_TEMPLATE_SIZE, filter = true)
    val values = FloatArray(FACE_TEMPLATE_SIZE * FACE_TEMPLATE_SIZE)

    var index = 0
    var sum = 0f
    for (y in 0 until FACE_TEMPLATE_SIZE) {
        for (x in 0 until FACE_TEMPLATE_SIZE) {
            val pixel = scaled.getPixel(x, y)
            val luminance = (
                (Color.red(pixel) * 0.299f) +
                    (Color.green(pixel) * 0.587f) +
                    (Color.blue(pixel) * 0.114f)
                ) / 255f
            values[index] = luminance
            sum += luminance
            index++
        }
    }

    val mean = sum / values.size
    var squaredSum = 0f
    for (i in values.indices) {
        values[i] -= mean
        squaredSum += values[i] * values[i]
    }

    val magnitude = sqrt(squaredSum.toDouble()).toFloat()
    if (magnitude <= 0.0001f) return null

    for (i in values.indices) {
        values[i] /= magnitude
    }
    return values
}

private fun cropFaceBitmap(bitmap: Bitmap, face: Face): Bitmap? {
    val box = face.boundingBox
    val left = box.left.coerceAtLeast(0)
    val top = box.top.coerceAtLeast(0)
    val right = box.right.coerceAtMost(bitmap.width)
    val bottom = box.bottom.coerceAtMost(bitmap.height)
    val width = right - left
    val height = bottom - top
    if (width <= 0 || height <= 0) return null
    return try {
        Bitmap.createBitmap(bitmap, left, top, width, height)
    } catch (e: Exception) {
        null
    }
}

private fun serializeFaceTemplate(features: FloatArray): String {
    val array = JSONArray()
    features.forEach { value -> array.put(value.toDouble()) }
    return array.toString()
}

private fun cosineSimilarity(first: FloatArray, second: FloatArray): Float {
    if (first.size != second.size || first.isEmpty()) return 0f

    var dotProduct = 0f
    for (index in first.indices) {
        dotProduct += first[index] * second[index]
    }
    
    // Vectors are already normalized in extractFaceFeatures, so similarity is just the dot product
    return dotProduct.coerceIn(-1.0f, 1.0f)
}

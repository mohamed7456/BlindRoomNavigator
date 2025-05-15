package com.example.blindnavigatorapplication

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import android.util.Log
import android.util.Size
import androidx.annotation.OptIn
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageProxy
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import kotlin.math.max
import kotlin.math.min
import java.util.HashMap
import android.graphics.ImageFormat
import android.graphics.Matrix
import java.io.ByteArrayOutputStream
import android.graphics.BitmapFactory
import android.graphics.Rect
import android.media.Image


class ObjectDetectorHelper(
    private val context: Context,
    private val doorModelFilename: String = "yolov8n_door_fine_tuned.tflite",
    private val obstacleModelFilename: String = "yolov8n.tflite",
    private val onDetection: (results: List<DetectionResult>, imageWidth: Int, imageHeight: Int) -> Unit
) {
    data class DetectionResult(
        val boundingBox: RectF,
        val label: String,
        val confidence: Float
    )

    private var interpreterDoor: Interpreter? = null
    private var interpreterObstacle: Interpreter? = null

    private val imageSizeDoor = Size(640, 640)
    private val imageSizeObstacle = Size(640, 640)

    private val labelsDoor = listOf("door")

    private val labelsObstacle = listOf(
        "person", "bicycle", "car", "motorcycle", "airplane", "bus", "train", "truck", "boat",
        "traffic light", "fire hydrant", "stop sign", "parking meter", "bench", "bird", "cat",
        "dog", "horse", "sheep", "cow", "elephant", "bear", "zebra", "giraffe", "backpack",
        "umbrella", "handbag", "tie", "suitcase", "frisbee", "skis", "snowboard", "sports ball",
        "kite", "baseball bat", "baseball glove", "skateboard", "surfboard", "tennis racket",
        "bottle", "wine glass", "cup", "fork", "knife", "spoon", "bowl", "banana", "apple",
        "sandwich", "orange", "broccoli", "carrot", "hot dog", "pizza", "donut", "cake",
        "chair", "couch", "potted plant", "bed", "dining table", "toilet", "tv",
        "laptop", "mouse", "remote", "keyboard", "cell phone", "microwave", "oven",
        "toaster", "sink", "refrigerator", "book", "clock", "vase", "scissors",
        "teddy bear", "hair drier", "toothbrush"
    )

    private val confidenceThresholdDoor = 0.3f
    private val confidenceThresholdObstacle = 0.6f

    private enum class ModelInputType {
        FLOAT32_NORMALIZED,
    }

    init {
        setupInterpreters()
    }

    private fun setupInterpreters() {
        try {
            val doorModelBuffer = loadModelFile(doorModelFilename)
            val obstacleModelBuffer = loadModelFile(obstacleModelFilename)
            val options = Interpreter.Options()

            interpreterDoor = Interpreter(doorModelBuffer, options)
            interpreterObstacle = Interpreter(obstacleModelBuffer, options)
            Log.d(TAG, "Door and Obstacle (YOLOv8 COCO) TFLite Interpreters initialized.")
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing TFLite Interpreters.", e)
        }
    }

    @Throws(IOException::class)
    private fun loadModelFile(modelFilename: String): MappedByteBuffer {
        context.assets.openFd(modelFilename).use { fd ->
            FileInputStream(fd.fileDescriptor).use { fis ->
                fis.channel.use { channel ->
                    return channel.map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength)
                }
            }
        }
    }

    @OptIn(ExperimentalGetImage::class)
    fun processImageProxy(imageProxy: ImageProxy) {
        if (interpreterDoor == null || interpreterObstacle == null) {
            Log.e(TAG, "Interpreters not ready."); imageProxy.close(); return
        }
        val mediaImage: Image? = imageProxy.image
        val rotationDegrees = imageProxy.imageInfo.rotationDegrees
        if (mediaImage == null) { Log.e(TAG, "MediaImage is null"); imageProxy.close(); return }

        val imageWidthForCallback: Int
        val imageHeightForCallback: Int
        if (rotationDegrees == 90 || rotationDegrees == 270) {
            imageWidthForCallback = imageProxy.height; imageHeightForCallback = imageProxy.width
        } else {
            imageWidthForCallback = imageProxy.width; imageHeightForCallback = imageProxy.height
        }

        val originalBitmap = mediaImage.toBitmap(rotationDegrees)
        if (originalBitmap == null) {
            Log.e(TAG, "Failed to convert ImageProxy to Bitmap");
            mediaImage.close(); imageProxy.close(); return
        }

        val results = runCombinedInference(originalBitmap)

        originalBitmap.recycle()
        mediaImage.close()
        imageProxy.close()
        onDetection(results, imageWidthForCallback, imageHeightForCallback)
    }

    private fun runYoloInference(
        interpreter: Interpreter?,
        bitmap: Bitmap,
        modelImageSize: Size,
        modelLabels: List<String>,
        modelConfidenceThreshold: Float,
        modelName: String,
        inputType: ModelInputType
    ): List<DetectionResult> {
        if (interpreter == null) {
            Log.e(TAG, "$modelName interpreter is null.")
            return emptyList()
        }

        val results = mutableListOf<DetectionResult>()
        val numElements = modelImageSize.width * modelImageSize.height * 3 // R, G, B
        val inputBuffer: ByteBuffer

        if (inputType == ModelInputType.FLOAT32_NORMALIZED) {
            inputBuffer = ByteBuffer.allocateDirect(numElements * 4).order(ByteOrder.nativeOrder())
            val intValues = IntArray(modelImageSize.width * modelImageSize.height)
            bitmap.getPixels(intValues, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            for (pixelValue in intValues) {
                inputBuffer.putFloat(((pixelValue shr 16) and 0xFF) / 255.0f) // R
                inputBuffer.putFloat(((pixelValue shr 8) and 0xFF) / 255.0f)  // G
                inputBuffer.putFloat((pixelValue and 0xFF) / 255.0f)          // B
            }
        } else { // ModelInputType.UINT8_0_255
            inputBuffer = ByteBuffer.allocateDirect(numElements * 1).order(ByteOrder.nativeOrder())
            val intValues = IntArray(modelImageSize.width * modelImageSize.height)
            bitmap.getPixels(intValues, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            for (pixelValue in intValues) {
                inputBuffer.put(((pixelValue shr 16) and 0xFF).toByte()) // R (0-255)
                inputBuffer.put(((pixelValue shr 8) and 0xFF).toByte())  // G (0-255)
                inputBuffer.put((pixelValue and 0xFF).toByte())          // B (0-255)
            }
        }
        inputBuffer.rewind()

        // Output setup for YOLO-style models
        val numClasses = modelLabels.size
        val numPredictions = 8400
        val outputElementCountPerPrediction = 4 + numClasses
        val flatOutputBuffer = ByteBuffer.allocateDirect(1 * outputElementCountPerPrediction * numPredictions * 4).order(ByteOrder.nativeOrder())
        val outputMap: MutableMap<Int, Any> = HashMap()
        outputMap[0] = flatOutputBuffer

        try {
            interpreter.runForMultipleInputsOutputs(arrayOf(inputBuffer), outputMap)
            flatOutputBuffer.rewind()
            val outputFloatBuffer = flatOutputBuffer.asFloatBuffer()

            for (i in 0 until numPredictions) {
                val cx = outputFloatBuffer.get(0 * numPredictions + i) * modelImageSize.width
                val cy = outputFloatBuffer.get(1 * numPredictions + i) * modelImageSize.height
                val w = outputFloatBuffer.get(2 * numPredictions + i) * modelImageSize.width
                val h = outputFloatBuffer.get(3 * numPredictions + i) * modelImageSize.height

                var maxScore = 0f
                var detectedClassIndex = -1
                for (c in 0 until numClasses) {
                    val score = outputFloatBuffer.get((4 + c) * numPredictions + i)
                    if (score > maxScore) {
                        maxScore = score
                        detectedClassIndex = c
                    }
                }

                if (maxScore >= modelConfidenceThreshold && detectedClassIndex != -1) {
                    val label = modelLabels[detectedClassIndex]
                    val rect = RectF(
                        max(0f, cx - w / 2f), max(0f, cy - h / 2f),
                        min(modelImageSize.width.toFloat(), cx + w / 2f), min(modelImageSize.height.toFloat(), cy + h / 2f)
                    )
                    results.add(DetectionResult(rect, label, maxScore))
                }
            }
            Log.d(TAG, "$modelName found ${results.size} objects.")

        } catch (e: Exception) {
            Log.e(TAG, "Error running/processing $modelName inference: ${e.message}", e)
        }
        return results
    }


    private fun runCombinedInference(originalBitmap: Bitmap): List<DetectionResult> {
        val combinedResults = mutableListOf<DetectionResult>()

        // --- 1. Door Model Inference ---
        val resizedBitmapDoor = Bitmap.createScaledBitmap(originalBitmap, imageSizeDoor.width, imageSizeDoor.height, true)
        val doorResults = runYoloInference(
            interpreterDoor, resizedBitmapDoor, imageSizeDoor, labelsDoor, confidenceThresholdDoor, "Door Model",
            ModelInputType.FLOAT32_NORMALIZED
        )
        combinedResults.addAll(doorResults)
        resizedBitmapDoor.recycle()

        // --- 2. Obstacle Model Inference ---
        val resizedBitmapObstacle = Bitmap.createScaledBitmap(originalBitmap, imageSizeObstacle.width, imageSizeObstacle.height, true)
        val obstacleRawResults = runYoloInference(
            interpreterObstacle, resizedBitmapObstacle, imageSizeObstacle, labelsObstacle, confidenceThresholdObstacle, "Obstacle Model (YOLOv8)",
            ModelInputType.FLOAT32_NORMALIZED
        )

        val doorAlreadyFound = combinedResults.any { it.label == "door" }
        for (obsResult in obstacleRawResults) {
            if (obsResult.label.equals("door", ignoreCase = true) && doorAlreadyFound) {
                continue
            }
            combinedResults.add(obsResult)
        }
        resizedBitmapObstacle.recycle()

        Log.d(TAG, "Total Combined Detections: ${combinedResults.size}")
        return combinedResults
    }

    private fun Image.toBitmap(rotationDegrees: Int): Bitmap? {
        if (format != ImageFormat.YUV_420_888) {
            Log.e(TAG, "Unsupported image format: $format")
            return null
        }
        val yBuffer = planes[0].buffer; val uBuffer = planes[1].buffer; val vBuffer = planes[2].buffer
        val ySize = yBuffer.remaining(); val uSize = uBuffer.remaining(); val vSize = vBuffer.remaining()
        val nv21 = ByteArray(ySize + uSize + vSize)
        yBuffer.get(nv21, 0, ySize); vBuffer.get(nv21, ySize, vSize); uBuffer.get(nv21, ySize + vSize, uSize)

        val yuvImage = android.graphics.YuvImage(nv21, ImageFormat.NV21, this.width, this.height, null)
        val out = ByteArrayOutputStream()
        if (!yuvImage.compressToJpeg(Rect(0, 0, this.width, this.height), 95, out)) {
            Log.e(TAG, "YuvImage compression failed.")
            return null
        }
        val imageBytes = out.toByteArray()
        var bmp: Bitmap? = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
        if (bmp == null) {
            Log.e(TAG, "BitmapFactory failed to decode byte array.")
            return null
        }

        if (rotationDegrees != 0) {
            val matrix = Matrix(); matrix.postRotate(rotationDegrees.toFloat())
            val rotatedBitmap = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, matrix, true)
            bmp.recycle(); bmp = rotatedBitmap
        }
        return bmp
    }

    fun cleanup() {
        interpreterDoor?.close(); interpreterObstacle?.close()
        interpreterDoor = null; interpreterObstacle = null
        Log.d(TAG, "Both TFLite Interpreters closed.")
    }

    companion object {
        private const val TAG = "ObjectDetectorHelper"
    }
}



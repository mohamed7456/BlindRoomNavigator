import android.content.Context
import android.util.Log
import androidx.annotation.OptIn
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageProxy
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.objects.ObjectDetection
import com.google.mlkit.vision.objects.DetectedObject
import com.google.mlkit.vision.objects.defaults.ObjectDetectorOptions

class ObjectDetectorHelper(context: Context, private val onDetection: (List<DetectedObject>) -> Unit) {
    private val options = ObjectDetectorOptions.Builder()
        .setDetectorMode(ObjectDetectorOptions.STREAM_MODE)
        .enableClassification()
        .build()

    private val objectDetector = ObjectDetection.getClient(options)

    @OptIn(ExperimentalGetImage::class)
    fun detectObjects(imageProxy: ImageProxy) {
        val image = InputImage.fromMediaImage(
            imageProxy.image!!,
            imageProxy.imageInfo.rotationDegrees
        )

        objectDetector.process(image)
            .addOnSuccessListener { detectedObjects ->
                onDetection(detectedObjects)
            }
            .addOnFailureListener { e ->
                Log.e("ObjectDetection", "Detection failed", e)
            }
            .addOnCompleteListener {
                imageProxy.close()
            }
    }

    fun cleanup() {
        objectDetector.close()
    }
}
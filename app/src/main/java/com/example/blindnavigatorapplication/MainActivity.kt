package com.example.blindnavigatorapplication

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.util.Log
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview as CameraPreview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Button
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.blindnavigatorapplication.ui.theme.BlindNavigatorApplicationTheme
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.objects.ObjectDetection
import com.google.mlkit.vision.objects.DetectedObject
import com.google.mlkit.vision.objects.defaults.ObjectDetectorOptions
import java.util.*
import java.util.concurrent.Executors

class MainActivity : ComponentActivity() {
    private val requiredPermissions = arrayOf(
        Manifest.permission.CAMERA,
        Manifest.permission.RECORD_AUDIO
    )

    private lateinit var permissionLauncher: ActivityResultLauncher<Array<String>>
    private var permissionsGranted by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        permissionLauncher = registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { grants ->
            permissionsGranted = grants.all { it.value }
        }

        setContent {
            BlindNavigatorApplicationTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    Box(modifier = Modifier.padding(innerPadding)) {
                        if (permissionsGranted || allPermissionsGranted()) {
                            CameraPreviewScreen()
                        } else {
                            PermissionRequestScreen {
                                permissionLauncher.launch(requiredPermissions)
                            }
                        }
                    }
                }
            }
        }
    }

    private fun allPermissionsGranted() = requiredPermissions.all {
        ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
    }
}

@OptIn(ExperimentalGetImage::class)
@Composable
fun CameraPreviewScreen() {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val cameraProviderFuture = remember { ProcessCameraProvider.getInstance(context) }
    var previewView by remember { mutableStateOf<PreviewView?>(null) }
    val cameraExecutor = remember { Executors.newSingleThreadExecutor() }
    var lastAnnouncementTime by remember { mutableStateOf(0L) }

    // TextToSpeech initialization
    var tts: TextToSpeech? by remember { mutableStateOf(null) }

    LaunchedEffect(Unit) {
        tts = TextToSpeech(context, object : TextToSpeech.OnInitListener {
            override fun onInit(status: Int) {
                if (status == TextToSpeech.SUCCESS) {
                    tts?.language = Locale.US
                }
            }
        })
    }

    // Object detection setup
    val objectDetector = remember {
        ObjectDetectorHelper(context) { detectedObjects ->
            val now = System.currentTimeMillis()
            if (now - lastAnnouncementTime > 2000) {
                handleDetectedObjects(detectedObjects, tts)
                lastAnnouncementTime = now
            }
        }
    }

    LaunchedEffect(previewView) {
        previewView?.let { pv ->
            val cameraProvider = cameraProviderFuture.get()
            val preview = CameraPreview.Builder().build().also {
                it.setSurfaceProvider(pv.surfaceProvider)
            }

            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also {
                    it.setAnalyzer(cameraExecutor) { imageProxy ->
                        if (imageProxy.image != null) {
                            objectDetector.detectObjects(imageProxy)
                        } else {
                            imageProxy.close()
                        }
                    }
                }

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    lifecycleOwner,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    analysis
                )
            } catch (exc: Exception) {
                Log.e("BlindNavigator", "Use case binding failed", exc)
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            tts?.shutdown()
            objectDetector.cleanup()
        }
    }

    CameraPreviewView(
        modifier = Modifier.fillMaxSize(),
        onInitialized = { previewView = it }
    )
}

class ObjectDetectorHelper(
    private val context: Context,
    private val onDetection: (List<DetectedObject>) -> Unit
) {
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

private fun handleDetectedObjects(
    detectedObjects: List<DetectedObject>,
    tts: TextToSpeech?
) {
    val obstacles = detectedObjects.filter {
        it.labels.any { label -> label.confidence > 0.5f }
    }

    tts?.let {
        if (obstacles.isEmpty()) {
            it.speak(
                "Move forward",
                TextToSpeech.QUEUE_FLUSH,
                null,
                "move_forward"
            )
        } else {
            val mainObstacle = obstacles.maxByOrNull { obj ->
                obj.boundingBox.width() * obj.boundingBox.height()
            }
            mainObstacle?.let { obstacle ->
                val screenCenter = 0.5f
                val obstacleCenterX = obstacle.boundingBox.exactCenterX() / screenCenter

                when {
                    obstacleCenterX < 0.4 -> it.speak(
                        "Obstacle left, move right",
                        TextToSpeech.QUEUE_FLUSH,
                        null,
                        "left_obstacle"
                    )
                    obstacleCenterX > 0.6 -> it.speak(
                        "Obstacle right, move left",
                        TextToSpeech.QUEUE_FLUSH,
                        null,
                        "right_obstacle"
                    )
                    else -> it.speak(
                        "Obstacle ahead, turn 45 degrees",
                        TextToSpeech.QUEUE_FLUSH,
                        null,
                        "center_obstacle"
                    )
                }
            }
        }
    }
}

@Composable
fun PermissionRequestScreen(onRequest: () -> Unit) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Camera and Audio permissions are required.", style = MaterialTheme.typography.bodyMedium)
            Spacer(modifier = Modifier.height(16.dp))
            Button(onClick = onRequest) {
                Text("Grant Permissions")
            }
        }
    }
}

@Composable
fun CameraPreviewView(
    modifier: Modifier = Modifier,
    onInitialized: (PreviewView) -> Unit
) {
    AndroidView(
        factory = { context ->
            PreviewView(context).apply {
                scaleType = PreviewView.ScaleType.FILL_CENTER
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                onInitialized(this)
            }
        },
        modifier = modifier
    )
}

@Composable
fun Greeting(name: String, modifier: Modifier = Modifier) {
    Text(
        text = "Hello $name!",
        modifier = modifier
    )
}

@Preview(showBackground = true)
@Composable
fun GreetingPreview() {
    BlindNavigatorApplicationTheme {
        Greeting("Android")
    }
}
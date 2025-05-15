package com.example.blindnavigatorapplication

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.example.blindnavigatorapplication.ui.theme.BlindNavigatorApplicationTheme
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : ComponentActivity(), TextToSpeech.OnInitListener {

    private lateinit var objectDetectorHelper: ObjectDetectorHelper
    private lateinit var navigationManager: NavigationManager
    private var tts: TextToSpeech? = null
    private lateinit var cameraExecutor: ExecutorService
    private lateinit var previewView: PreviewView

    private val ttsInitialized = mutableStateOf(false)

    private val activityResultLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
            var permissionGranted = true
            permissions.entries.forEach {
                if (it.key in REQUIRED_PERMISSIONS && !it.value)
                    permissionGranted = false
            }
            if (!permissionGranted) {
                Toast.makeText(this, "Camera permission request denied.", Toast.LENGTH_SHORT).show()
            } else {
                // Permissions granted, proceed with camera setup via LaunchedEffect
                // The LaunchedEffect observing permissions will trigger startCamera
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        tts = TextToSpeech(this, this)
        cameraExecutor = Executors.newSingleThreadExecutor()

        setContent {
            BlindNavigatorApplicationTheme {
                val context = LocalContext.current
//                val lifecycleOwner = LocalLifecycleOwner.current
                val cameraProviderFuture = remember { ProcessCameraProvider.getInstance(context) }
                val permissionsGranted = remember { mutableStateOf(allPermissionsGranted()) }

                objectDetectorHelper = remember {
                    ObjectDetectorHelper(context) { results, width, height ->
                        if (::navigationManager.isInitialized) {
                            navigationManager.handleDetections(results, width, height)
                        } else {
                            Log.w(TAG, "NavigationManager not ready, skipping detection handling.")
                        }
                    }
                }

                LaunchedEffect(ttsInitialized.value) {
                    if (ttsInitialized.value) {
                        navigationManager = NavigationManager(tts)
                        Log.d(TAG, "NavigationManager initialized.")
                    }
                }


                Box(modifier = Modifier.fillMaxSize()) {
                    AndroidView(
                        factory = { ctx ->
                            previewView = PreviewView(ctx).apply {
                                implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                            }
                            previewView
                        },
                        modifier = Modifier.fillMaxSize(),
                        update = { /* No specific update needed here for PreviewView itself */ }
                    )
                }

                // Effect to handle permissions and start camera
                LaunchedEffect(permissionsGranted.value, cameraProviderFuture) {
                    if (!permissionsGranted.value) {
                        requestPermissions()
                    } else {
                        val cameraProvider = cameraProviderFuture.get()
                        bindCameraUseCases(cameraProvider)
                    }
                }
                LaunchedEffect(Unit) {
                    permissionsGranted.value = allPermissionsGranted()
                }
            }
        }
    }

    private fun handleDetectionResults(results: List<ObjectDetectorHelper.DetectionResult>, imageWidth: Int, imageHeight: Int) {
        if (::navigationManager.isInitialized) {
            runOnUiThread {
                navigationManager.handleDetections(results, imageWidth, imageHeight)
            }
        } else {
            Log.w(TAG, "NavigationManager not ready, skipping detection handling.")
        }
    }


    private fun bindCameraUseCases(cameraProvider: ProcessCameraProvider) {
        val cameraSelector = CameraSelector.Builder()
            .requireLensFacing(CameraSelector.LENS_FACING_BACK)
            .build()

        val preview = Preview.Builder()
            .build()
            .also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }

        val imageAnalysis = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()
            .also {
                it.setAnalyzer(cameraExecutor) { imageProxy ->
                    objectDetectorHelper.processImageProxy(imageProxy)
                }
            }

        try {
            cameraProvider.unbindAll()

            cameraProvider.bindToLifecycle(
                this,
                cameraSelector,
                preview,
                imageAnalysis
            )
            Log.d(TAG, "Camera use cases bound successfully.")

        } catch (exc: Exception) {
            Log.e(TAG, "Use case binding failed", exc)
            Toast.makeText(this, "Error starting camera: ${exc.message}", Toast.LENGTH_SHORT).show()
        }
    }

    // --- Permissions Handling ---

    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(baseContext, it) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestPermissions() {
        activityResultLauncher.launch(REQUIRED_PERMISSIONS)
    }

    // --- TextToSpeech.OnInitListener Implementation ---

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val result = tts?.setLanguage(Locale.US) // Set language, check result
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                Log.e(TAG, "TTS language (US English) not supported or missing data.")
                Toast.makeText(this, "TTS Language not supported.", Toast.LENGTH_SHORT).show()
                tts = null
            } else {
                Log.d(TAG, "TTS Engine Initialized successfully.")
            }
        } else {
            Log.e(TAG, "TTS Initialization Failed! Status: $status")
            Toast.makeText(this, "TTS Initialization Failed.", Toast.LENGTH_SHORT).show()
            tts = null
        }
        ttsInitialized.value = true
    }


    override fun onResume() {
        super.onResume()
        if (allPermissionsGranted()) {
        } else if (!shouldShowRequestPermissionRationale(Manifest.permission.CAMERA)){
        }
    }

    override fun onPause() {
        super.onPause()
        // Stop TTS speech if playing
        tts?.stop()
        Log.d(TAG, "TTS stopped in onPause.")
        // Camera use cases are paused by bindToLifecycle automatically
    }

    override fun onDestroy() {
        super.onDestroy()
        // Shutdown TTS
        tts?.stop()
        tts?.shutdown()
        Log.d(TAG, "TTS shutdown.")

        // Shutdown Camera Executor
        cameraExecutor.shutdown()
        Log.d(TAG, "Camera executor shutdown.")

        // Cleanup detector resources
        if (::objectDetectorHelper.isInitialized) {
            objectDetectorHelper.cleanup()
        }
    }

    companion object {
        private const val TAG = "MainActivity"
        private val REQUIRED_PERMISSIONS = arrayOf(Manifest.permission.CAMERA)
    }
}
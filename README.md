# BlindRoomNavigator

BlindRoomNavigator is an Android application designed to assist visually impaired users in navigating indoor environments. It uses real-time object detection (YOLOv8 models) to identify doors and obstacles, providing spoken navigation instructions via Text-to-Speech (TTS).

## Features

- **Real-time Object Detection:** Uses TensorFlow Lite models to detect doors and obstacles from the device camera.
- **Audio Guidance:** Provides spoken instructions to help users avoid obstacles and locate doors.
- **Custom Fine-tuned Models:** Supports both a general obstacle model and a fine-tuned door detection model.
- **Modern Android Architecture:** Built with Jetpack Compose, CameraX, and Kotlin.

## Getting Started

### Prerequisites

- Android Studio (Giraffe or newer recommended)
- Android device or emulator (API 34+)
- [TensorFlow Lite](https://www.tensorflow.org/lite) support

### Installation

1. **Clone the repository:**
    ```sh
    git clone https://github.com/mohamed7456/BlindRoomNavigator.git
    cd BlindRoomNavigator
    ```

2. **Open in Android Studio:**  
   Open the project folder in Android Studio.

3. **Build the project:**  
   Gradle will automatically download dependencies.

4. **Run on device:**  
   Connect your Android device (with camera) and run the app.

### Model Files

The app uses two TFLite models stored in [`app/src/main/assets/`](app/src/main/assets/):

- `yolov8n_doors_fine_tuned.tflite` — Fine-tuned for door detection
- `yolov8n.tflite` — General obstacle detection

THe provided Jupyter notebook in [`model_notebook/`](model_notebook/) was used to fine tune the door detection model.

### Model Evaluation

Key evaluation plots (confusion matrix, PR curve, F1 curve, etc.) from the fine-tuning process are available in [`model_notebook/runs/`](model_notebook/runs/).

## Project Structure

- [`app/`](app/) — Main Android application source code
    - [`src/main/java/com/example/blindnavigatorapplication/`](app/src/main/java/com/example/blindnavigatorapplication/)
        - [`MainActivity.kt`](app/src/main/java/com/example/blindnavigatorapplication/MainActivity.kt): App entry point
        - [`ObjectDetectorHelper.kt`](app/src/main/java/com/example/blindnavigatorapplication/ObjectDetectorHelper.kt): Handles TFLite model inference
        - [`NavigationManager.kt`](app/src/main/java/com/example/blindnavigatorapplication/NavigationManager.kt): Generates navigation instructions
    - [`src/main/assets/`](app/src/main/assets/): TFLite model files
    - [`src/main/res/`](app/src/main/res/): UI resources
- [`model_notebook/`](model_notebook/): Notebooks for model training and fine-tuning

## Usage

1. Grant camera permission when prompted.
2. The app will start the camera and begin detecting doors and obstacles.
3. Spoken instructions will guide you to avoid obstacles and find doors.

## Customization

- **Instruction Logic:**  
  Modify [`NavigationManager`](app/src/main/java/com/example/blindnavigatorapplication/NavigationManager.kt) for custom navigation strategies.

## License

This project is licensed under the MIT License. See [`LICENSE`](LICENSE) for details.

---

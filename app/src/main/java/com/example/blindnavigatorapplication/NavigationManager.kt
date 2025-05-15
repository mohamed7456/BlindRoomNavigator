package com.example.blindnavigatorapplication

import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.util.Log
import com.example.blindnavigatorapplication.ObjectDetectorHelper.DetectionResult
import kotlin.math.abs
import kotlin.math.roundToInt

class NavigationManager(
    private val tts: TextToSpeech?,
    private val announcementDelay: Long = DEFAULT_ANNOUNCEMENT_DELAY,
    private val stableInfoDelay: Long = DEFAULT_STABLE_INFO_DELAY
) {
    companion object {
        private const val TAG = "NavigationManager"
        private const val DEFAULT_ANNOUNCEMENT_DELAY = 5000L
        private const val DEFAULT_STABLE_INFO_DELAY = 7000L
        private const val MIN_OBSTACLE_HEIGHT_RATIO_FOR_NEAR = 0.20f
//        private const val MAX_OBSTACLE_DEVIATION_FOR_FRONTAL = 0.30f
        private const val HORIZONTAL_FOV_DEGREES = 70.0
        private const val MIN_TURN_ANGLE_DEGREES = 15
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private var lastInstructionKey = ""
    private var lastInstructionTime = 0L
    private var currentDoor: DetectionResult? = null
    private val currentObstacles = mutableListOf<DetectionResult>()

    private enum class DistanceCategory { UNKNOWN, VERY_CLOSE, CLOSE, MEDIUM, FAR }
    private enum class RelativePosition { UNKNOWN, CENTER, SLIGHT_LEFT, SLIGHT_RIGHT, LEFT, RIGHT, FAR_LEFT, FAR_RIGHT }

    fun handleDetections(detections: List<DetectionResult>, screenW: Int, screenH: Int) {
        currentObstacles.clear()
        currentDoor = detections.filter { it.label.equals("door", true) }
            .maxByOrNull { it.confidence }

        currentObstacles.addAll(detections.filterNot { it.label.equals("door", true) })

        val (instructionKey, instructionText) = generateInstruction(screenW, screenH)
        val now = System.currentTimeMillis()
        val isNewInstruction = instructionKey != lastInstructionKey
        val elapsedSinceLast = now - lastInstructionTime

        if ((isNewInstruction && elapsedSinceLast >= announcementDelay) ||
            (!isNewInstruction && elapsedSinceLast >= stableInfoDelay)
        ) {
            if (instructionText.isNotBlank()) {
                Log.i(TAG, "Announcing: [$instructionKey] $instructionText (LastKey: $lastInstructionKey, Elapsed: $elapsedSinceLast ms, New: $isNewInstruction)")
                speak(instructionText)
                lastInstructionKey = instructionKey
                lastInstructionTime = now
            }
        } else {
            Log.d(TAG, "Skipping announcement for key: $instructionKey, New: $isNewInstruction, Elapsed: $elapsedSinceLast")
        }
    }

    private fun generateInstruction(sw: Int, sh: Int): Pair<String, String> {
        val frontalNearObstacle = findFrontalNearObstacle(sw, sh)
        if (frontalNearObstacle != null) {
            return provideObstacleAvoidanceInstruction(frontalNearObstacle, currentDoor, sw, sh)
        }
        currentDoor?.let { door ->
            return provideDoorDirectionInstruction(door, sw, sh)
        }
        val anyNearObstacle = findAnyNearObstacle(sh)
        if (anyNearObstacle != null) {
            val obsPos = getRelativePosition(anyNearObstacle.boundingBox.centerX(), sw)
            val posText = getPositionText(obsPos, true)
            val key = "OBSTACLE_NEAR_${anyNearObstacle.label.uppercase()}_${obsPos}"
            val text = "${anyNearObstacle.label.replaceFirstChar(Char::titlecase)} $posText, proceed with caution."
            return key to text
        }
        return "NO_RELEVANT_OBJECT" to "Path appears clear. Scan for the door."
    }

    private fun provideDoorDirectionInstruction(door: DetectionResult, sw: Int, sh: Int): Pair<String, String> {
        val doorBox = door.boundingBox
        val doorCenterX = doorBox.centerX()
        val calculatedAngle = calculateAngleDegrees(doorCenterX, sw)
        val absCalculatedAngle = abs(calculatedAngle)

        Log.d(TAG, "--- Door Guidance ---")
        Log.d(TAG, "Screen Width (sw): $sw, Screen Height (sh): $sh, Screen Center: ${sw/2f}")
        Log.d(TAG, "Door BBox: Left=${doorBox.left}, Right=${doorBox.right}, Top=${doorBox.top}, Bottom=${doorBox.bottom}")
        Log.d(TAG, "Door CenterX: $doorCenterX, Deviation Pixels: ${doorCenterX - (sw/2f)}")
        Log.d(TAG, "Calculated Angle: $calculatedAngle, AbsCalculatedAngle: $absCalculatedAngle")

        val distCategory = getDistanceCategory(doorBox, sh)
        val distText = distCategory.toString().lowercase().replace("_", " ")

        var actionText: String
        var descriptivePositionText: String
        var keySuffix: String
        var determinedLogicBranch = "UNKNOWN"

        when {
            absCalculatedAngle < MIN_TURN_ANGLE_DEGREES -> {
                descriptivePositionText = "straight ahead"
                actionText = if (distCategory == DistanceCategory.VERY_CLOSE || distCategory == DistanceCategory.CLOSE) {
                    "Door is very close. Approach carefully."
                } else {
                    "Move forward."
                }
                keySuffix = "CENTER"
                determinedLogicBranch = "CENTER (absAngle < $MIN_TURN_ANGLE_DEGREES)"
            }
            calculatedAngle < 0 -> {
                val relPos = getRelativePosition(doorCenterX, sw)
                descriptivePositionText = getPositionText(relPos)
                actionText = "Turn about $absCalculatedAngle degrees to your left."
                keySuffix = "LEFT_$absCalculatedAngle"
                determinedLogicBranch = "LEFT (angle < 0)"
            }
            else -> {
                val relPos = getRelativePosition(doorCenterX, sw)
                descriptivePositionText = getPositionText(relPos)
                actionText = "Turn about $absCalculatedAngle degrees to your right."
                keySuffix = "RIGHT_$absCalculatedAngle"
                determinedLogicBranch = "RIGHT (angle >= 0 and not CENTER)"
            }
        }
        Log.d(TAG, "Determined Logic Branch: $determinedLogicBranch, DescPos: $descriptivePositionText, Action: $actionText")

        val key = "DOOR_${keySuffix}_$distCategory"
        val text = "Door $descriptivePositionText, approximately $distText. $actionText"
        return key to text
    }

    private fun getDistanceCategory(box: RectF, screenH: Int): DistanceCategory {
        if (screenH <= 0) return DistanceCategory.UNKNOWN
        val heightRatio = box.height() / screenH.toFloat()
        return when {
            heightRatio > 0.50f -> DistanceCategory.VERY_CLOSE
            heightRatio > MIN_OBSTACLE_HEIGHT_RATIO_FOR_NEAR -> DistanceCategory.CLOSE
            heightRatio > 0.05f -> DistanceCategory.MEDIUM
            else -> DistanceCategory.FAR
        }
    }

    private fun getRelativePosition(objectCenterX: Float, screenW: Int): RelativePosition {
        if (screenW <= 0) return RelativePosition.UNKNOWN
        val screenCenter = screenW / 2f
        val deviationRatio = (objectCenterX - screenCenter) / screenCenter
        Log.d(TAG, "RelativePosition: objectCenterX=$objectCenterX, screenCenter=$screenCenter, deviationRatio=$deviationRatio")
        return when {
            abs(deviationRatio) < 0.10f -> RelativePosition.CENTER
            deviationRatio < -0.60f -> RelativePosition.FAR_LEFT
            deviationRatio < -0.25f -> RelativePosition.LEFT
            deviationRatio < 0f -> RelativePosition.SLIGHT_LEFT
            deviationRatio > 0.60f -> RelativePosition.FAR_RIGHT
            deviationRatio > 0.25f -> RelativePosition.RIGHT
            else -> RelativePosition.SLIGHT_RIGHT
        }
    }

    private fun getPositionText(position: RelativePosition, isObstacle: Boolean = false): String {
        return when(position) {
            RelativePosition.CENTER -> if (isObstacle) "directly ahead" else "straight ahead"
            RelativePosition.SLIGHT_LEFT -> "slightly to your left"
            RelativePosition.LEFT -> "to your left"
            RelativePosition.FAR_LEFT -> "far to your left"
            RelativePosition.SLIGHT_RIGHT -> "slightly to your right"
            RelativePosition.RIGHT -> "to your right"
            RelativePosition.FAR_RIGHT -> "far to your right"
            RelativePosition.UNKNOWN -> "at an unknown position"
        }
    }

    private fun calculateAngleDegrees(objectCenterX: Float, screenW: Int): Int {
        if (screenW <= 0) return 0
        val screenCenter = screenW / 2f
        val deviationPixels = objectCenterX - screenCenter
        val halfScreenWidth = screenW / 2.0
        if (abs(halfScreenWidth) < 1e-6) return 0

        val tanHalfFoV = kotlin.math.tan(Math.toRadians(HORIZONTAL_FOV_DEGREES / 2.0))
        val normalizedDeviationProjection = (deviationPixels * tanHalfFoV) / halfScreenWidth
        var angle = Math.toDegrees(kotlin.math.atan(normalizedDeviationProjection))
        angle = angle.coerceIn(-HORIZONTAL_FOV_DEGREES, HORIZONTAL_FOV_DEGREES)
        val roundedAngle = angle.roundToInt()
        Log.d(TAG, "AngleCalc: objCenterX=$objectCenterX, sw=$screenW, devPix=$deviationPixels, normDevProj=$normalizedDeviationProjection, angle=$angle, roundedAngle=$roundedAngle")
        return roundedAngle
    }

    private fun findFrontalNearObstacle(sw: Int, sh: Int): DetectionResult? {
        return currentObstacles.filter { obs ->
            val distCat = getDistanceCategory(obs.boundingBox, sh)
            distCat == DistanceCategory.VERY_CLOSE || distCat == DistanceCategory.CLOSE
        }.filter { obs ->
            val relPos = getRelativePosition(obs.boundingBox.centerX(), sw)
            relPos == RelativePosition.CENTER || relPos == RelativePosition.SLIGHT_LEFT || relPos == RelativePosition.SLIGHT_RIGHT
        }.maxByOrNull { it.boundingBox.width() * it.boundingBox.height() }
    }

    private fun findAnyNearObstacle(sh: Int): DetectionResult? {
        return currentObstacles.filter { obs ->
            val distCat = getDistanceCategory(obs.boundingBox, sh)
            distCat == DistanceCategory.VERY_CLOSE || distCat == DistanceCategory.CLOSE
        }.maxByOrNull { it.confidence }
    }

    private fun provideObstacleAvoidanceInstruction(
        obstacle: DetectionResult,
        door: DetectionResult?,
        sw: Int, sh: Int
    ): Pair<String, String> {
        val obsLabel = obstacle.label.replaceFirstChar(Char::titlecase)
        val obsDistCat = getDistanceCategory(obstacle.boundingBox, sh)
        val obsDistText = obsDistCat.toString().lowercase().replace("_", " ")
        var instructionText = "Stop. $obsLabel $obsDistText ahead. "
        var instructionKey = "OBSTACLE_FRONTAL_${obstacle.label.uppercase()}_${obsDistCat}"
        val obsCenterX = obstacle.boundingBox.centerX()
        var passRight = obsCenterX > sw / 2f

        if (door != null) {
            val doorCenterX = door.boundingBox.centerX()
            val obsLeftEdge = obstacle.boundingBox.left
            val obsRightEdge = obstacle.boundingBox.right
            if (doorCenterX < obsLeftEdge) {
                passRight = false
                instructionText += "Door is to its left. "
            } else if (doorCenterX > obsRightEdge) {
                passRight = true
                instructionText += "Door is to its right. "
            }
        }

        if (passRight) {
            instructionText += "Try moving slightly to your right to pass."
            instructionKey += "_PASS_RIGHT"
        } else {
            instructionText += "Try moving slightly to your left to pass."
            instructionKey += "_PASS_LEFT"
        }
        return instructionKey to instructionText
    }

    private fun speak(msg: String) {
        if (tts == null) {
            Log.e(TAG, "TTS is null, cannot speak: $msg")
            return
        }
        mainHandler.post {
            tts.speak(msg, TextToSpeech.QUEUE_FLUSH, null, msg.hashCode().toString())
        }
    }
}
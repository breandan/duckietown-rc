/* Copyright 2019 The TensorFlow Authors. All Rights Reserved.

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
==============================================================================*/

package edu.umontreal.duckietownrc.detection.tracking

import android.content.Context
import android.graphics.*
import android.graphics.Color.*
import android.graphics.Paint.Cap
import android.graphics.Paint.Join
import android.graphics.Paint.Style
import android.text.TextUtils
import android.util.Pair
import android.util.TypedValue
import android.widget.Toast
import edu.umontreal.duckietownrc.detection.env.BorderedText
import edu.umontreal.duckietownrc.detection.env.ImageUtils
import edu.umontreal.duckietownrc.detection.env.Logger
import edu.umontreal.duckietownrc.detection.tflite.Classifier.Recognition

import java.util.LinkedList
import java.util.Queue

class MultiBoxTracker(private val context: Context) {
    private val TEXT_SIZE_DIP = 18f
    // Maximum percentage of a box that can be overlapped by another box at detection time. Otherwise
    // the lower scored box (new or old) will be removed.
    private val MAX_OVERLAP = 0.2f
    private val MIN_SIZE = 16.0f
    // Allow replacement of the tracked box with new results if
    // correlation has dropped below this level.
    private val MARGINAL_CORRELATION = 0.75f
    // Consider object to be lost if correlation falls below this threshold.
    private val MIN_CORRELATION = 0.3f
    private val COLORS = intArrayOf(
        BLUE,
        RED,
        GREEN,
        YELLOW,
        CYAN,
        MAGENTA,
        WHITE,
        parseColor("#55FF55"),
        parseColor("#FFA500"),
        parseColor("#FF8888"),
        parseColor("#AAAAFF"),
        parseColor("#FFFFAA"),
        parseColor("#55AAAA"),
        parseColor("#AA33AA"),
        parseColor("#0D0068")
    )

    internal val screenRects: MutableList<Pair<Float, RectF>> = LinkedList()
    private val logger = Logger()
    private val availableColors = LinkedList<Int>(COLORS.asList())
    private val trackedObjects = LinkedList<TrackedRecognition>()
    private val boxPaint = Paint().apply {
        color = RED
        style = Style.STROKE
        strokeWidth = 10.0f
        strokeCap = Cap.ROUND
        strokeJoin = Join.ROUND
        strokeMiter = 100f
    }

    private val textSizePx: Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, TEXT_SIZE_DIP, context.resources.displayMetrics)

    private val borderedText = BorderedText(textSizePx)
    private var objectTracker: ObjectTracker? = null
    private var frameToCanvasMatrix: Matrix? = null
    private var frameWidth: Int = 0
    private var frameHeight: Int = 0
    private var sensorOrientation: Int = 0
    private var initialized = false

    @Synchronized
    fun drawDebug(canvas: Canvas) {
        val textPaint = Paint()
        textPaint.color = WHITE
        textPaint.textSize = 60.0f

        val boxPaint = Paint().apply {
            color = RED
            alpha = 200
            style = Style.STROKE
        }

        for (detection in screenRects) {
            val rect = detection.second
            canvas.drawRect(rect, boxPaint)
            canvas.drawText("" + detection.first, rect.left, rect.top, textPaint)
            borderedText.drawText(canvas, rect.centerX(), rect.centerY(), "" + detection.first)
        }

        if (objectTracker == null) return

        // Draw correlations.
        for (recognition in trackedObjects) {
            val trackedObject = recognition.trackedObject

            val trackedPos = trackedObject!!.trackedPositionInPreviewFrame

            if (frameToCanvasMatrix!!.mapRect(trackedPos)) {
                val labelString = String.format("%.2f", trackedObject.currentCorrelation)
                borderedText.drawText(canvas, trackedPos!!.right, trackedPos.bottom, labelString)
            }
        }

        val matrix = frameToCanvasMatrix
        objectTracker!!.drawDebug(canvas, matrix!!)
    }

    @Synchronized
    fun trackResults(results: List<Recognition>, frame: ByteArray, timestamp: Long) {
        logger.i("Processing %d results from %d", results.size, timestamp)
        processResults(timestamp, results, frame)
    }

    @Synchronized
    fun draw(canvas: Canvas) {
        val rotated = sensorOrientation % 180 == 90
        val multiplier = Math.min(
            canvas.height / (if (rotated) frameWidth else frameHeight).toFloat(),
            canvas.width / (if (rotated) frameHeight else frameWidth).toFloat()
        )
        frameToCanvasMatrix = ImageUtils.getTransformationMatrix(
            frameWidth,
            frameHeight,
            (multiplier * if (rotated) frameHeight else frameWidth).toInt(),
            (multiplier * if (rotated) frameWidth else frameHeight).toInt(),
            sensorOrientation,
            false
        )
        for (recognition in trackedObjects) {
            val trackedPos = if (objectTracker != null) recognition.trackedObject!!.trackedPositionInPreviewFrame!!
            else RectF(recognition.location)

            frameToCanvasMatrix!!.mapRect(trackedPos)
            boxPaint.color = recognition.color

            val cornerSize = 1.0f
            canvas.drawRoundRect(trackedPos, cornerSize, cornerSize, boxPaint)

            val labelString = if (!TextUtils.isEmpty(recognition.title))
                String.format("%s %.2f", recognition.title, 100 * recognition.detectionConfidence)
            else
                String.format("%.2f", 100 * recognition.detectionConfidence)
            //            borderedText.drawText(canvas, trackedPos.left + cornerSize, trackedPos.top,
            // labelString);
            borderedText.drawText(
                canvas, trackedPos.left + cornerSize, trackedPos.top, "$labelString%", boxPaint
            )
        }
    }

    @Synchronized
    fun onFrame(w: Int, h: Int, rowStride: Int, sensorOrientation: Int, frame: ByteArray, timestamp: Long) {
        if (objectTracker == null && !initialized) {
            ObjectTracker.clearInstance()

            logger.i("Initializing ObjectTracker: %dx%d", w, h)
            objectTracker = ObjectTracker.getInstance(w, h, rowStride, true)
            frameWidth = w
            frameHeight = h
            this.sensorOrientation = sensorOrientation
            initialized = true
        }

        if (objectTracker == null) return

        (objectTracker as ObjectTracker).nextFrame(frame, null, timestamp, null, true)

        // Clean up any objects not worth tracking any more.
        val copyList = LinkedList(trackedObjects)
        for (recognition in copyList) {
            val trackedObject = recognition.trackedObject
            val correlation = trackedObject!!.currentCorrelation
            if (correlation < MIN_CORRELATION) {
                logger.v("Removing tracked object %s because NCC is %.2f", trackedObject, correlation)
                trackedObject.stopTracking()
                trackedObjects.remove(recognition)

                availableColors.add(recognition.color)
            }
        }
    }

    private fun processResults(timestamp: Long, results: List<Recognition>, originalFrame: ByteArray) {
        val rectsToTrack = LinkedList<Pair<Float, Recognition>>()

        screenRects.clear()
        val rgbFrameToScreen = Matrix(frameToCanvasMatrix)

        for (result in results) {
            if (result.location == null) continue
            val detectionFrameRect = RectF(result.location)

            val detectionScreenRect = RectF()
            rgbFrameToScreen.mapRect(detectionScreenRect, detectionFrameRect)

            logger.v("Result! Frame: " + result.location + " mapped to screen:" + detectionScreenRect)

            screenRects.add(Pair<Float, RectF>(result.confidence, detectionScreenRect))

            if (detectionFrameRect.width() < MIN_SIZE || detectionFrameRect.height() < MIN_SIZE) {
                logger.w("Degenerate rectangle! $detectionFrameRect")
                continue
            }

            rectsToTrack.add(Pair<Float, Recognition>(result.confidence, result))
        }

        if (rectsToTrack.isEmpty()) {
            logger.v("Nothing to track, aborting.")
            return
        }

        if (objectTracker == null) {
            trackedObjects.clear()
            for (potential in rectsToTrack) {
                val trackedRecognition = TrackedRecognition()
                trackedRecognition.detectionConfidence = potential.first
                trackedRecognition.location = RectF(potential.second.location)
                trackedRecognition.trackedObject = null
                trackedRecognition.title = potential.second.title
                trackedRecognition.color = COLORS[trackedObjects.size]
                trackedObjects.add(trackedRecognition)

                if (trackedObjects.size >= COLORS.size) break
            }
            return
        }

        logger.i("%d rects to track", rectsToTrack.size)
        for (potential in rectsToTrack) handleDetection(originalFrame, timestamp, potential)
    }

    private fun handleDetection(frameCopy: ByteArray, timestamp: Long, potential: Pair<Float, Recognition>) {
        val potentialObject = objectTracker!!.trackObject(potential.second.location!!, timestamp, frameCopy)

        val potentialCorrelation = potentialObject.currentCorrelation
        logger.v(
            "Tracked object went from %s to %s with correlation %.2f",
            potential.second, potentialObject.trackedPositionInPreviewFrame!!, potentialCorrelation
        )

        if (potentialCorrelation < MARGINAL_CORRELATION) {
            logger.v("Correlation too low to begin tracking %s.", potentialObject)
            potentialObject.stopTracking()
            return
        }

        val removeList = LinkedList<TrackedRecognition>()

        var maxIntersect = 0.0f

        // This is the current tracked object whose color we will take. If left null we'll take the
        // first one from the color queue.
        var recogToReplace: TrackedRecognition? = null

        // Look for intersections that will be overridden by this object or an intersection that would
        // prevent this one from being placed.
        for (trackedRecognition in trackedObjects) {
            val a = trackedRecognition.trackedObject!!.trackedPositionInPreviewFrame
            val b = potentialObject.trackedPositionInPreviewFrame
            val intersection = RectF()
            val intersects = intersection.setIntersect(a, b)

            val intersectArea = intersection.width() * intersection.height()
            val totalArea = a!!.width() * a.height() + b!!.width() * b.height() - intersectArea
            val intersectOverUnion = intersectArea / totalArea

            // If there is an intersection with this currently tracked box above the maximum overlap
            // percentage allowed, either the new recognition needs to be dismissed or the old
            // recognition needs to be removed and possibly replaced with the new one.
            if (intersects && intersectOverUnion > MAX_OVERLAP)
                if (potential.first < trackedRecognition.detectionConfidence && trackedRecognition.trackedObject!!.currentCorrelation > MARGINAL_CORRELATION) {
                    // If track for the existing object is still going strong and the detection score was
                    // good, reject this new object.
                    potentialObject.stopTracking()
                    return
                } else {
                    removeList.add(trackedRecognition)

                    // Let the previously tracked object with max intersection amount donate its color to
                    // the new object.
                    if (intersectOverUnion > maxIntersect) {
                        maxIntersect = intersectOverUnion
                        recogToReplace = trackedRecognition
                    }
                }
        }

        // If we're already tracking the max object and no intersections were found to bump off,
        // pick the worst current tracked object to remove, if it's also worse than this candidate
        // object.
        if (availableColors.isEmpty() && removeList.isEmpty()) {
            for (candidate in trackedObjects)
                if (candidate.detectionConfidence < potential.first)
                // Save it so that we use this color for the new object.
                    if (recogToReplace == null || candidate.detectionConfidence < recogToReplace.detectionConfidence)
                        recogToReplace = candidate
            if (recogToReplace != null) {
                logger.v("Found non-intersecting object to remove.")
                removeList.add(recogToReplace)
            } else logger.v("No non-intersecting object found to remove")
        }

        // Remove everything that got intersected.
        for (trackedRecognition in removeList) {
            logger.v(
                "Removing tracked object %s with detection confidence %.2f, correlation %.2f",
                trackedRecognition.trackedObject!!,
                trackedRecognition.detectionConfidence,
                trackedRecognition.trackedObject!!.currentCorrelation
            )
            trackedRecognition.trackedObject!!.stopTracking()
            trackedObjects.remove(trackedRecognition)
            if (trackedRecognition !== recogToReplace) availableColors.add(trackedRecognition.color)
        }

        if (recogToReplace == null && availableColors.isEmpty()) {
            logger.e("No room to track this object, aborting.")
            potentialObject.stopTracking()
            return
        }

        // Finally safe to say we can track this object.
        logger.v(
            "Tracking object %s (%s) with detection confidence %.2f at position %s",
            potentialObject,
            potential.second.title!!,
            potential.first,
            potential.second.location!!
        )

        TrackedRecognition().apply {
            detectionConfidence = potential.first
            trackedObject = potentialObject
            title = potential.second.title

            // Use the color from a replaced object before taking one from the color queue.
            color = recogToReplace?.color ?: availableColors.poll()
            trackedObjects.add(this)
        }
    }

    private class TrackedRecognition {
        internal var trackedObject: ObjectTracker.TrackedObject? = null
        internal var location: RectF? = null
        internal var detectionConfidence: Float = 0.toFloat()
        internal var color: Int = 0
        internal var title: String? = null
    }
}

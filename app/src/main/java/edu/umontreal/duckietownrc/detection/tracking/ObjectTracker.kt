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

import android.graphics.*
import edu.umontreal.duckietownrc.detection.env.Logger
import edu.umontreal.duckietownrc.detection.env.Size

import javax.microedition.khronos.opengles.GL10
import java.util.*

class ObjectTracker protected constructor(
    protected val frameWidth: Int,
    protected val frameHeight: Int,
    private val rowStride: Int,
    protected val alwaysTrack: Boolean
) {
    private val downsampledFrame: ByteArray
    private val trackedObjects: MutableMap<String, TrackedObject>
    private val debugHistory: Vector<PointF>
    private val timestampedDeltas: LinkedList<TimestampedDeltas>
    private val matrixValues = FloatArray(9)
    private var lastTimestamp: Long = 0
    private var lastKeypoints: FrameChange? = null
    private var downsampledTimestamp: Long = 0
    /**
     * This will contain an opaque pointer to the native ObjectTracker
     */
    private val nativeObjectTracker: Long = 0

    val debugText: Vector<String>
        get() {
            val lines = Vector<String>()

            if (lastKeypoints != null) {
                lines.add("Num keypoints " + lastKeypoints!!.pointDeltas.size)
                lines.add("Min score: " + lastKeypoints!!.minScore)
                lines.add("Max score: " + lastKeypoints!!.maxScore)
            }

            return lines
        }

    init {
        this.timestampedDeltas = LinkedList()

        trackedObjects = HashMap()

        debugHistory = Vector(MAX_DEBUG_HISTORY_SIZE)

        downsampledFrame =
            ByteArray((frameWidth + DOWNSAMPLE_FACTOR - 1) / DOWNSAMPLE_FACTOR * (frameWidth + DOWNSAMPLE_FACTOR - 1) / DOWNSAMPLE_FACTOR)
    }

    protected fun init() {
        // The native tracker never sees the full frame, so pre-scale dimensions
        // by the downsample factor.
        initNative(frameWidth / DOWNSAMPLE_FACTOR, frameHeight / DOWNSAMPLE_FACTOR, alwaysTrack)
    }

    @Synchronized
    fun drawOverlay(
        gl: GL10, cameraViewSize: Size, matrix: Matrix
    ) {
        val tempMatrix = Matrix(matrix)
        tempMatrix.preScale(DOWNSAMPLE_FACTOR.toFloat(), DOWNSAMPLE_FACTOR.toFloat())
        tempMatrix.getValues(matrixValues)
        drawNative(cameraViewSize.width, cameraViewSize.height, matrixValues)
    }

    @Synchronized
    fun nextFrame(
        frameData: ByteArray?,
        uvData: ByteArray?,
        timestamp: Long,
        transformationMatrix: FloatArray?,
        updateDebugInfo: Boolean
    ) {
        if (downsampledTimestamp != timestamp) {
            ObjectTracker.downsampleImageNative(
                frameWidth, frameHeight, rowStride, frameData, DOWNSAMPLE_FACTOR, downsampledFrame
            )
            downsampledTimestamp = timestamp
        }

        // Do Lucas Kanade using the fullframe initializer.
        nextFrameNative(downsampledFrame, uvData, timestamp, transformationMatrix)

        timestampedDeltas.add(TimestampedDeltas(timestamp, getKeypointsPacked(DOWNSAMPLE_FACTOR.toFloat())))
        while (timestampedDeltas.size > MAX_FRAME_HISTORY_SIZE) timestampedDeltas.removeFirst()

        for (trackedObject in trackedObjects.values) trackedObject.updateTrackedPosition()

        if (updateDebugInfo) updateDebugHistory()

        lastTimestamp = timestamp
    }

    @Synchronized
    fun release() {
        releaseMemoryNative()
        synchronized(ObjectTracker::class.java) {
            instance = null
        }
    }

    private fun drawHistoryDebug(canvas: Canvas) {
        drawHistoryPoint(
            canvas, (frameWidth * DOWNSAMPLE_FACTOR / 2).toFloat(), (frameHeight * DOWNSAMPLE_FACTOR / 2).toFloat()
        )
    }

    private fun drawHistoryPoint(canvas: Canvas, startX: Float, startY: Float) {
        val paint = Paint().apply {
            isAntiAlias = false
            typeface = Typeface.SERIF

            color = Color.RED
            strokeWidth = 2.0f

            // Draw the center circle.
            color = Color.GREEN
            canvas.drawCircle(startX, startY, 3.0f, this)

            color = Color.RED
        }

        // Iterate through in backwards order.
        synchronized(debugHistory) {
            val numPoints = debugHistory.size
            var lastX = startX
            var lastY = startY
            for (keypointNum in 0 until numPoints) {
                val delta = debugHistory[numPoints - keypointNum - 1]
                val newX = lastX + delta.x
                val newY = lastY + delta.y
                canvas.drawLine(lastX, lastY, newX, newY, paint)
                lastX = newX
                lastY = newY
            }
        }
    }

    private fun drawKeypointsDebug(canvas: Canvas) {
        val paint = Paint()
        if (lastKeypoints == null) return
        val keypointSize = 3

        val minScore = lastKeypoints!!.minScore
        val maxScore = lastKeypoints!!.maxScore

        for (keypoint in lastKeypoints!!.pointDeltas) {
            if (keypoint.wasFound) {
                val r = floatToChar((keypoint.keypointA.score - minScore) / (maxScore - minScore))
                val b = floatToChar(1.0f - (keypoint.keypointA.score - minScore) / (maxScore - minScore))

                val color = -0x1000000 or (r shl 16) or b
                paint.color = color

                val screenPoints =
                    floatArrayOf(keypoint.keypointA.x, keypoint.keypointA.y, keypoint.keypointB.x, keypoint.keypointB.y)
                canvas.drawRect(
                    screenPoints[2] - keypointSize,
                    screenPoints[3] - keypointSize,
                    screenPoints[2] + keypointSize,
                    screenPoints[3] + keypointSize,
                    paint
                )
                paint.color = Color.CYAN
                canvas.drawLine(screenPoints[2], screenPoints[3], screenPoints[0], screenPoints[1], paint)

                if (DRAW_TEXT) {
                    paint.color = Color.WHITE
                    canvas.drawText(
                        keypoint.keypointA.type.toString() + ": " + keypoint.keypointA.score,
                        keypoint.keypointA.x,
                        keypoint.keypointA.y,
                        paint
                    )
                }
            } else {
                paint.color = Color.YELLOW
                val screenPoint = floatArrayOf(keypoint.keypointA.x, keypoint.keypointA.y)
                canvas.drawCircle(screenPoint[0], screenPoint[1], 5.0f, paint)
            }
        }
    }

    @Synchronized
    private fun getAccumulatedDelta(timestamp: Long, positionX: Float, positionY: Float, radius: Float) =
        getCurrentPosition(
            timestamp,
            RectF(positionX - radius, positionY - radius, positionX + radius, positionY + radius)
        ).run { PointF(centerX() - positionX, centerY() - positionY) }

    @Synchronized
    private fun getCurrentPosition(timestamp: Long, oldPosition: RectF): RectF {
        val downscaledFrameRect = downscaleRect(oldPosition)

        val delta = FloatArray(4)
        getCurrentPositionNative(
            timestamp,
            downscaledFrameRect.left,
            downscaledFrameRect.top,
            downscaledFrameRect.right,
            downscaledFrameRect.bottom,
            delta
        )

        val newPosition = RectF(delta[0], delta[1], delta[2], delta[3])

        return upscaleRect(newPosition)
    }

    private fun updateDebugHistory() {
        lastKeypoints = FrameChange(getKeypointsNative(false))

        if (lastTimestamp == 0L) return

        val delta = getAccumulatedDelta(
            lastTimestamp,
            (frameWidth / DOWNSAMPLE_FACTOR).toFloat(),
            (frameHeight / DOWNSAMPLE_FACTOR).toFloat(),
            100f
        )

        synchronized(debugHistory) {
            debugHistory.add(delta)

            while (debugHistory.size > MAX_DEBUG_HISTORY_SIZE) debugHistory.removeAt(0)
        }
    }

    @Synchronized
    fun drawDebug(canvas: Canvas, frameToCanvas: Matrix) {
        canvas.save()
        canvas.matrix = frameToCanvas

        drawHistoryDebug(canvas)
        drawKeypointsDebug(canvas)

        canvas.restore()
    }

    @Synchronized
    fun pollAccumulatedFlowData(endFrameTime: Long): List<ByteArray> {
        val frameDeltas = ArrayList<ByteArray>()
        while (timestampedDeltas.size > 0) {
            val currentDeltas = timestampedDeltas.peek()
            if (currentDeltas.timestamp <= endFrameTime) {
                frameDeltas.add(currentDeltas.deltas)
                timestampedDeltas.removeFirst()
            } else break
        }

        return frameDeltas
    }

    private fun downscaleRect(fullFrameRect: RectF): RectF {
        return RectF(
            fullFrameRect.left / DOWNSAMPLE_FACTOR,
            fullFrameRect.top / DOWNSAMPLE_FACTOR,
            fullFrameRect.right / DOWNSAMPLE_FACTOR,
            fullFrameRect.bottom / DOWNSAMPLE_FACTOR
        )
    }

    private fun upscaleRect(downsampledFrameRect: RectF): RectF {
        return RectF(
            downsampledFrameRect.left * DOWNSAMPLE_FACTOR,
            downsampledFrameRect.top * DOWNSAMPLE_FACTOR,
            downsampledFrameRect.right * DOWNSAMPLE_FACTOR,
            downsampledFrameRect.bottom * DOWNSAMPLE_FACTOR
        )
    }

    @Synchronized
    fun trackObject(
        position: RectF, timestamp: Long, frameData: ByteArray
    ): TrackedObject {
        if (downsampledTimestamp != timestamp) {
            ObjectTracker.downsampleImageNative(
                frameWidth, frameHeight, rowStride, frameData, DOWNSAMPLE_FACTOR, downsampledFrame
            )
            downsampledTimestamp = timestamp
        }
        return TrackedObject(position, timestamp, downsampledFrame)
    }

    @Synchronized
    fun trackObject(position: RectF, frameData: ByteArray) =
        TrackedObject(position, lastTimestamp, frameData)

    private external fun initNative(imageWidth: Int, imageHeight: Int, alwaysTrack: Boolean)

    protected external fun registerNewObjectWithAppearanceNative(
        objectId: String, x1: Float, y1: Float, x2: Float, y2: Float, data: ByteArray
    )

    protected external fun setPreviousPositionNative(
        objectId: String, x1: Float, y1: Float, x2: Float, y2: Float, timestamp: Long
    )

    /**
     * ******************** NATIVE CODE ************************************
     */
    protected external fun setCurrentPositionNative(
        objectId: String, x1: Float, y1: Float, x2: Float, y2: Float
    )

    protected external fun forgetNative(key: String)

    protected external fun getModelIdNative(key: String): String

    protected external fun haveObject(key: String): Boolean

    protected external fun isObjectVisible(key: String): Boolean

    protected external fun getCurrentCorrelation(key: String): Float

    protected external fun getMatchScore(key: String): Float

    protected external fun getTrackedPositionNative(key: String, points: FloatArray)

    protected external fun nextFrameNative(
        frameData: ByteArray?, uvData: ByteArray?, timestamp: Long, frameAlignMatrix: FloatArray?
    )

    protected external fun releaseMemoryNative()

    protected external fun getCurrentPositionNative(
        timestamp: Long,
        positionX1: Float,
        positionY1: Float,
        positionX2: Float,
        positionY2: Float,
        delta: FloatArray
    )

    protected external fun getKeypointsPacked(scaleFactor: Float): ByteArray

    protected external fun getKeypointsNative(onlyReturnCorrespondingKeypoints: Boolean): FloatArray

    protected external fun drawNative(viewWidth: Int, viewHeight: Int, frameToCanvas: FloatArray?)

    private class TimestampedDeltas(internal val timestamp: Long, internal val deltas: ByteArray)

    /**
     * A simple class that records keypoint information, which includes local location, score and
     * type. This will be used in calculating FrameChange.
     */
    class Keypoint {
        val x: Float
        val y: Float
        val score: Float
        val type: Int

        constructor(x: Float, y: Float) {
            this.x = x
            this.y = y
            this.score = 0f
            this.type = -1
        }

        constructor(x: Float, y: Float, score: Float, type: Int) {
            this.x = x
            this.y = y
            this.score = score
            this.type = type
        }

        internal fun delta(other: Keypoint): Keypoint {
            return Keypoint(this.x - other.x, this.y - other.y)
        }
    }

    /**
     * A simple class that could calculate Keypoint delta. This class will be used in calculating
     * frame translation delta for optical flow.
     */
    class PointChange(
        x1: Float,
        y1: Float,
        x2: Float,
        y2: Float,
        score: Float,
        type: Int,
        val wasFound: Boolean
    ) {
        val keypointA: Keypoint = Keypoint(x1, y1, score, type)
        val keypointB: Keypoint = Keypoint(x2, y2)
    }

    /**
     * A class that records a timestamped frame translation delta for optical flow.
     */
    class FrameChange(framePoints: FloatArray) {

        val pointDeltas: Vector<PointChange>

        val minScore: Float
        val maxScore: Float
        val KEYPOINT_STEP = 7

        init {
            var minScore = 100.0f
            var maxScore = -100.0f

            pointDeltas = Vector(framePoints.size / KEYPOINT_STEP)

            var i = 0
            while (i < framePoints.size) {
                val x1 = framePoints[i + 0] * DOWNSAMPLE_FACTOR
                val y1 = framePoints[i + 1] * DOWNSAMPLE_FACTOR

                val wasFound = framePoints[i + 2] > 0.0f

                val x2 = framePoints[i + 3] * DOWNSAMPLE_FACTOR
                val y2 = framePoints[i + 4] * DOWNSAMPLE_FACTOR
                val score = framePoints[i + 5]
                val type = framePoints[i + 6].toInt()

                minScore = Math.min(minScore, score)
                maxScore = Math.max(maxScore, score)

                pointDeltas.add(PointChange(x1, y1, x2, y2, score, type, wasFound))
                i += KEYPOINT_STEP
            }

            this.minScore = minScore
            this.maxScore = maxScore
        }
    }

    /**
     * A TrackedObject represents a native TrackedObject, and provides access to the relevant native
     * tracking information available after every frame update. They may be safely passed around and
     * accessed externally, but will become invalid after stopTracking() is called or the related
     * creating ObjectTracker is deactivated.
     *
     * @author andrewharp@google.com (Andrew Harp)
     */
    inner class TrackedObject internal constructor(position: RectF, timestamp: Long, data: ByteArray) {
        private val id: String

        @get:Synchronized
        internal var lastExternalPositionTime: Long = 0
            private set

        private var lastTrackedPosition: RectF? = null
        private var visibleInLastFrame: Boolean = false

        private var isDead: Boolean = false

        val currentCorrelation: Float
            get() {
                checkValidObject()
                return this@ObjectTracker.getCurrentCorrelation(id)
            }

        val trackedPositionInPreviewFrame: RectF?
            @Synchronized get() {
                checkValidObject()

                return if (lastTrackedPosition == null) null else upscaleRect(lastTrackedPosition!!)
            }

        init {
            isDead = false

            id = Integer.toString(this.hashCode())

            lastExternalPositionTime = timestamp

            synchronized(this@ObjectTracker) {
                registerInitialAppearance(position, data)
                setPreviousPosition(position, timestamp)
                trackedObjects.put(id, this)
            }
        }

        fun stopTracking() {
            checkValidObject()

            synchronized(this@ObjectTracker) {
                isDead = true
                forgetNative(id)
                trackedObjects.remove(id)
            }
        }

        internal fun registerInitialAppearance(position: RectF, data: ByteArray) {
            val externalPosition = downscaleRect(position)
            registerNewObjectWithAppearanceNative(
                id,
                externalPosition.left,
                externalPosition.top,
                externalPosition.right,
                externalPosition.bottom,
                data
            )
        }

        @Synchronized
        internal fun setPreviousPosition(position: RectF, timestamp: Long) {
            checkValidObject()
            synchronized(this@ObjectTracker) {
                if (lastExternalPositionTime > timestamp) {
                    LOGGER.w("Tried to use older position time!")
                    return
                }
                val externalPosition = downscaleRect(position)
                lastExternalPositionTime = timestamp

                setPreviousPositionNative(
                    id,
                    externalPosition.left,
                    externalPosition.top,
                    externalPosition.right,
                    externalPosition.bottom,
                    lastExternalPositionTime
                )

                updateTrackedPosition()
            }
        }

        internal fun setCurrentPosition(position: RectF) {
            checkValidObject()
            val downsampledPosition = downscaleRect(position)
            synchronized(this@ObjectTracker) {
                setCurrentPositionNative(
                    id,
                    downsampledPosition.left,
                    downsampledPosition.top,
                    downsampledPosition.right,
                    downsampledPosition.bottom
                )
            }
        }

        @Synchronized
        fun updateTrackedPosition() {
            checkValidObject()

            val delta = FloatArray(4)
            getTrackedPositionNative(id, delta)
            lastTrackedPosition = RectF(delta[0], delta[1], delta[2], delta[3])

            visibleInLastFrame = isObjectVisible(id)
        }

        @Synchronized
        fun visibleInLastPreviewFrame() = visibleInLastFrame

        private fun checkValidObject() {
            if (isDead)
                throw RuntimeException("TrackedObject already removed from tracking!")
            else if (this@ObjectTracker !== instance)
                throw RuntimeException("TrackedObject created with another ObjectTracker!")
        }
    }

    companion object {
        private val LOGGER = Logger()
        private val DRAW_TEXT = false
        /**
         * How many history points to keep track of and draw in the red history line.
         */
        private val MAX_DEBUG_HISTORY_SIZE = 30
        /**
         * How many frames of optical flow deltas to record. TODO(andrewharp): Push this down to the
         * native level so it can be polled efficiently into a an array for upload, instead of keeping a
         * duplicate copy in Java.
         */
        private val MAX_FRAME_HISTORY_SIZE = 200

        private val DOWNSAMPLE_FACTOR = 2
        protected var instance: ObjectTracker? = null
        private var libraryFound = false

        init {
            try {
                System.loadLibrary("tensorflow_demo")
                libraryFound = true
            } catch (e: UnsatisfiedLinkError) {
                LOGGER.e("libtensorflow_demo.so not found, tracking unavailable")
            }
        }

        @Synchronized
        fun getInstance(
            frameWidth: Int, frameHeight: Int, rowStride: Int, alwaysTrack: Boolean
        ): ObjectTracker? {
            if (!libraryFound) {
                LOGGER.e("Native object tracking support not found. See tensorflow/examples/android/README.md for details.")
                return null
            }

            if (instance == null) {
                instance = ObjectTracker(frameWidth, frameHeight, rowStride, alwaysTrack)
                instance!!.init()
            } else throw RuntimeException("Tried to create a new objectracker before releasing the old one!")
            return instance
        }

        @Synchronized
        fun clearInstance() {
            if (instance != null) instance!!.release()
        }

        private fun floatToChar(value: Float) = Math.max(0, Math.min((value * 255.999f).toInt(), 255))

        protected external fun downsampleImageNative(
            width: Int, height: Int, rowStride: Int, input: ByteArray?, factor: Int, output: ByteArray?
        )
    }
}

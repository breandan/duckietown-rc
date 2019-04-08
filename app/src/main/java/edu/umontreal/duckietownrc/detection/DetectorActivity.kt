/*
 * Copyright 2019 The TensorFlow Authors. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package edu.umontreal.duckietownrc.detection

import android.graphics.*
import android.graphics.Bitmap.Config
import android.graphics.Paint.Style
import android.media.ImageReader.OnImageAvailableListener
import android.os.SystemClock
import android.util.Size
import android.util.TypedValue
import android.widget.Toast
import android.widget.Toast.*
import edu.umontreal.duckietownrc.R
import edu.umontreal.duckietownrc.detection.customview.OverlayView
import edu.umontreal.duckietownrc.detection.env.BorderedText
import edu.umontreal.duckietownrc.detection.env.ImageUtils
import edu.umontreal.duckietownrc.detection.env.Logger
import edu.umontreal.duckietownrc.detection.tflite.Classifier
import edu.umontreal.duckietownrc.detection.tflite.TFLiteObjectDetectionAPIModel
import edu.umontreal.duckietownrc.detection.tracking.MultiBoxTracker
import java.io.IOException
import java.util.*

/**
 * An activity that uses a TensorFlowMultiBoxDetector and ObjectTracker to detect and then track
 * objects.
 */
class DetectorActivity : CameraActivity(), OnImageAvailableListener {
    lateinit var trackingOverlay: OverlayView
    private var sensorOrientation: Int? = null

    private var detector: Classifier? = null

    private var lastProcessingTimeMs: Long = 0
    private var rgbFrameBitmap: Bitmap? = null
    private var croppedBitmap: Bitmap? = null
    private var cropCopyBitmap: Bitmap? = null

    private var computingDetection = false

    private var timestamp: Long = 0

    private var frameToCropTransform: Matrix? = null
    private var cropToFrameTransform: Matrix? = null

    private var tracker: MultiBoxTracker? = null

    private var luminanceCopy: ByteArray? = null

    private var borderedText: BorderedText? = null

    public override fun onPreviewSizeChosen(size: Size, rotation: Int) {
        val textSizePx = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, TEXT_SIZE_DIP, resources.displayMetrics)
        borderedText = BorderedText(textSizePx)
        borderedText!!.setTypeface(Typeface.MONOSPACE)

        tracker = MultiBoxTracker(this)

        var cropSize = TF_OD_API_INPUT_SIZE

        try {
            detector = TFLiteObjectDetectionAPIModel.create(
                assets,
                TF_OD_API_MODEL_FILE,
                TF_OD_API_LABELS_FILE,
                TF_OD_API_INPUT_SIZE,
                TF_OD_API_IS_QUANTIZED
            )
            cropSize = TF_OD_API_INPUT_SIZE
        } catch (e: IOException) {
            e.printStackTrace()
            LOGGER.e("Exception initializing classifier!", e)
            val toast = makeText(applicationContext, "Classifier could not be initialized", LENGTH_SHORT)
            toast.show()
            finish()
        }

        previewWidth = size.width
        previewHeight = size.height

        sensorOrientation = rotation - screenOrientation
        LOGGER.i("Camera orientation relative to screen canvas: %d", sensorOrientation!!)

        LOGGER.i("Initializing at size %dx%d", previewWidth, previewHeight)
        rgbFrameBitmap = Bitmap.createBitmap(previewWidth, previewHeight, Config.ARGB_8888)
        croppedBitmap = Bitmap.createBitmap(cropSize, cropSize, Config.ARGB_8888)

        frameToCropTransform = ImageUtils.getTransformationMatrix(
            previewWidth, previewHeight,
            cropSize, cropSize,
            sensorOrientation!!, MAINTAIN_ASPECT
        )

        cropToFrameTransform = Matrix()
        frameToCropTransform!!.invert(cropToFrameTransform)

        trackingOverlay = findViewById(R.id.tracking_overlay)
        trackingOverlay.addCallback(object : OverlayView.DrawCallback {
            override fun drawCallback(canvas: Canvas) {
                tracker!!.draw(canvas)
                if (isDebug) tracker!!.drawDebug(canvas)
            }
        })
    }

    override fun processImage() {
        ++timestamp
        val currTimestamp = timestamp
        val originalLuminance = luminance
        tracker!!.onFrame(
            previewWidth,
            previewHeight,
            luminanceStride,
            sensorOrientation!!,
            originalLuminance,
            timestamp
        )
        trackingOverlay.postInvalidate()

        // No mutex needed as this method is not reentrant.
        if (computingDetection) {
            readyForNextImage()
            return
        }
        computingDetection = true
        LOGGER.i("Preparing image $currTimestamp for detection in bg thread.")

        imageConverter!!.run()
        rgbFrameBitmap!!.setPixels(rgbBytes, 0, previewWidth, 0, 0, previewWidth, previewHeight)

        if (luminanceCopy == null) luminanceCopy = ByteArray(originalLuminance.size)
        System.arraycopy(originalLuminance, 0, luminanceCopy, 0, originalLuminance.size)
        readyForNextImage()

        val canvas = Canvas(croppedBitmap!!)
        canvas.drawBitmap(rgbFrameBitmap!!, frameToCropTransform!!, null)
        // For examining the actual TF input.
        if (SAVE_PREVIEW_BITMAP) ImageUtils.saveBitmap(croppedBitmap as Bitmap)

        runInBackground (Runnable {
            LOGGER.i("Running detection on image $currTimestamp")
            val startTime = SystemClock.uptimeMillis()
            val results = detector!!.recognizeImage(croppedBitmap!!)
            lastProcessingTimeMs = SystemClock.uptimeMillis() - startTime

            cropCopyBitmap = Bitmap.createBitmap(croppedBitmap!!)
            val canvas1 = Canvas(cropCopyBitmap!!)
            val paint = Paint()
            paint.color = Color.RED
            paint.style = Style.STROKE
            paint.strokeWidth = 2.0f

            var minimumConfidence = MINIMUM_CONFIDENCE_TF_OD_API
            when (MODE) {
                DetectorActivity.DetectorMode.TF_OD_API -> minimumConfidence = MINIMUM_CONFIDENCE_TF_OD_API
            }

            val mappedRecognitions = LinkedList<Classifier.Recognition>()

            for (result in results) {
                val location = result.location
                if (location != null && result.confidence!! >= minimumConfidence) {
                    canvas1.drawRect(location, paint)

                    cropToFrameTransform!!.mapRect(location)

                    result.location = location
                    mappedRecognitions.add(result)
                }
            }

            tracker!!.trackResults(mappedRecognitions, luminanceCopy as ByteArray, currTimestamp)
            trackingOverlay.postInvalidate()

            computingDetection = false
        })
    }

    // Which detection model to use: by default uses Tensorflow Object Detection API frozen
    // checkpoints.
    private enum class DetectorMode { TF_OD_API }

    private val LOGGER = Logger()

    // Configuration values for the prepackaged SSD model.
    private val TF_OD_API_INPUT_SIZE = 300
    private val TF_OD_API_IS_QUANTIZED = true
    private val TF_OD_API_MODEL_FILE = "detect.tflite"
    private val TF_OD_API_LABELS_FILE = "file:///android_asset/labelmap.txt"
    private val MODE = DetectorMode.TF_OD_API
    // Minimum detection confidence to track a detection.
    private val MINIMUM_CONFIDENCE_TF_OD_API = 0.4f
    private val MAINTAIN_ASPECT = false
    private val DESIRED_PREVIEW_SIZE = Size(640, 480)
    private val SAVE_PREVIEW_BITMAP = false
    private val TEXT_SIZE_DIP = 10f

    override val layoutId = R.layout.camera_connection_fragment_tracking
    override val desiredPreviewFrameSize = DESIRED_PREVIEW_SIZE
}
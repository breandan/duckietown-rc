package edu.umontreal.duckietownrc.detection

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

import android.app.Fragment
import android.graphics.SurfaceTexture
import android.hardware.Camera
import android.hardware.Camera.CameraInfo
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.util.Size
import android.util.SparseIntArray
import android.view.*
import edu.umontreal.duckietownrc.R
import edu.umontreal.duckietownrc.detection.customview.AutoFitTextureView
import edu.umontreal.duckietownrc.detection.env.ImageUtils
import edu.umontreal.duckietownrc.detection.env.Logger

import java.io.IOException

class LegacyCameraConnectionFragment(
    private val imageListener: Camera.PreviewCallback,
    /** The layout identifier to inflate for this Fragment.  */
    private val layout: Int, private val desiredSize: Size
) : Fragment() {
    private val LOGGER = Logger()

    private var camera: Camera? = null
    /** An [AutoFitTextureView] for camera preview.  */
    private var textureView: AutoFitTextureView? = null
    /**
     * [TextureView.SurfaceTextureListener] handles several lifecycle events on a [ ].
     */
    private val surfaceTextureListener = object : TextureView.SurfaceTextureListener {
        override fun onSurfaceTextureAvailable(
            texture: SurfaceTexture, width: Int, height: Int
        ) {

            val index = cameraId
            camera = Camera.open(index)

            try {
                val parameters = camera!!.parameters
                val focusModes = parameters.supportedFocusModes
                if (focusModes != null && focusModes.contains(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE)) {
                    parameters.focusMode = Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE
                }
                val cameraSizes = parameters.supportedPreviewSizes

                val previewSize = CameraConnectionFragment.chooseOptimalSize(
                    cameraSizes.map { Size(it.width, it.height) }.toTypedArray(), desiredSize.width, desiredSize.height
                )
                parameters.setPreviewSize(previewSize.width, previewSize.height)
                camera!!.setDisplayOrientation(90)
                camera!!.parameters = parameters
                camera!!.setPreviewTexture(texture)
            } catch (exception: IOException) {
                camera!!.release()
            }

            camera!!.setPreviewCallbackWithBuffer(imageListener)
            val s = camera!!.parameters.previewSize
            camera!!.addCallbackBuffer(ByteArray(ImageUtils.getYUVByteSize(s.height, s.width)))

            textureView!!.setAspectRatio(s.height, s.width)

            camera!!.startPreview()
        }

        override fun onSurfaceTextureSizeChanged(
            texture: SurfaceTexture, width: Int, height: Int
        ) {
        }

        override fun onSurfaceTextureDestroyed(texture: SurfaceTexture) = true

        override fun onSurfaceTextureUpdated(texture: SurfaceTexture) {}
    }
    /** An additional thread for running tasks that shouldn't block the UI.  */
    private var backgroundThread: HandlerThread? = null

    private// No camera found
    val cameraId: Int
        get() {
            val ci = CameraInfo()
            for (i in 0 until Camera.getNumberOfCameras()) {
                Camera.getCameraInfo(i, ci)
                if (ci.facing == CameraInfo.CAMERA_FACING_BACK) return i
            }
            return -1
        }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ) = inflater.inflate(layout, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        textureView = view.findViewById(R.id.texture)
    }

    override fun onActivityCreated(savedInstanceState: Bundle?) = super.onActivityCreated(savedInstanceState)

    override fun onResume() {
        super.onResume()
        startBackgroundThread()
        // When the screen is turned off and turned back on, the SurfaceTexture is already
        // available, and "onSurfaceTextureAvailable" will not be called. In that case, we can open
        // a camera and start preview from here (otherwise, we wait until the surface is ready in
        // the SurfaceTextureListener).

        if (textureView!!.isAvailable) camera!!.startPreview() else textureView!!.surfaceTextureListener = surfaceTextureListener
    }

    override fun onPause() {
        stopCamera()
        stopBackgroundThread()
        super.onPause()
    }

    /** Starts a background thread and its [Handler].  */
    private fun startBackgroundThread() {
        backgroundThread = HandlerThread("CameraBackground")
        backgroundThread!!.start()
    }

    /** Stops the background thread and its [Handler].  */
    private fun stopBackgroundThread() {
        backgroundThread!!.quitSafely()
        try {
            backgroundThread!!.join()
            backgroundThread = null
        } catch (e: InterruptedException) {
            LOGGER.e(e, "Exception!")
        }
    }

    protected fun stopCamera() {
        if (camera != null) {
            camera!!.stopPreview()
            camera!!.setPreviewCallback(null)
            camera!!.release()
            camera = null
        }
    }
}

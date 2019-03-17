/*
 * Copyright (C) 2013 OSRF.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package edu.umontreal.duckietownrc

import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.Button
import com.github.rosjava.android_remocons.common_tools.apps.RosAppActivity
import org.ros.android.BitmapFromCompressedImage
import org.ros.android.view.RosImageView
import org.ros.android.view.VirtualJoystickView
import org.ros.node.NodeConfiguration
import org.ros.node.NodeMainExecutor
import java.io.IOException

class MainActivity : RosAppActivity("android duckietown", "android duckietown") {
    private var cameraView: RosImageView<sensor_msgs.CompressedImage>? = null
    private var virtualJoystickView: VirtualJoystickView? = null
    private var backButton: Button? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        setDashboardResource(R.id.top_bar)
        setMainWindowResource(R.layout.main)
        super.onCreate(savedInstanceState)

        cameraView = findViewById<View>(R.id.image) as RosImageView<sensor_msgs.CompressedImage>
        cameraView!!.setMessageType(sensor_msgs.CompressedImage._TYPE)
        cameraView!!.setMessageToBitmapCallable(BitmapFromCompressedImage())
        virtualJoystickView = findViewById<View>(R.id.virtual_joystick) as VirtualJoystickView
        backButton = findViewById<View>(R.id.back_button) as Button
        backButton!!.setOnClickListener { onBackPressed() }
    }

    override fun init(nodeMainExecutor: NodeMainExecutor) {
        super.init(nodeMainExecutor)

        try {
            val socket = java.net.Socket(masterUri.host, masterUri.port)
            val localNetworkAddress = socket.localAddress
            socket.close()
            val nodeConfiguration = NodeConfiguration.newPublic(localNetworkAddress.hostAddress, masterUri)

            var joyTopic = remaps.get(getString(R.string.joystick_topic))
            var camTopic = remaps.get(getString(R.string.camera_topic))

            val appNameSpace = masterNameSpace
            joyTopic = appNameSpace.resolve(joyTopic).toString()
            camTopic = appNameSpace.resolve(camTopic).toString()

            cameraView!!.setTopicName(camTopic)
            virtualJoystickView!!.setTopicName(joyTopic)

            nodeMainExecutor.execute(
                cameraView, nodeConfiguration
                    .setNodeName("android/camera_view")
            )
            nodeMainExecutor.execute(
                virtualJoystickView,
                nodeConfiguration.setNodeName("android/virtual_joystick")
            )
        } catch (e: IOException) {
            // Socket problem
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(0, 0, 0, R.string.stop_app)
        return super.onCreateOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        super.onOptionsItemSelected(item)
        if (item.itemId == 0) onDestroy()
        return true
    }
}

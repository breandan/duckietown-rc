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
import com.github.rosjava.android_remocons.common_tools.apps.RosAppActivity
import kotlinx.android.synthetic.main.main.*
import org.ros.android.BitmapFromCompressedImage
import org.ros.android.view.RosImageView
import org.ros.node.NodeConfiguration
import org.ros.node.NodeMainExecutor
import java.io.IOException
import java.net.Socket


class MainActivity : RosAppActivity("android duckietown", "android duckietown") {
    override fun onCreate(savedInstanceState: Bundle?) {
        setDashboardResource(R.id.top_bar)
        setMainWindowResource(R.layout.main)
        super.onCreate(savedInstanceState)

        image.setMessageType(sensor_msgs.CompressedImage._TYPE)
        (image as RosImageView<sensor_msgs.CompressedImage>).setMessageToBitmapCallable(BitmapFromCompressedImage())
        back_button.setOnClickListener { onBackPressed() }
    }

    override fun init(nodeMainExecutor: NodeMainExecutor) {
        super.init(nodeMainExecutor)

        try {
            val nodeConfiguration = Socket(masterUri.host, masterUri.port).use {
                NodeConfiguration.newPublic(it.localAddress.hostAddress, masterUri)
            }

            val joyTopic = getRemap(R.string.joystick_topic)
            val camTopic = getRemap(R.string.camera_topic)

            image.setTopicName(camTopic)
            virtual_joystick.setTopicName(joyTopic)

            nodeMainExecutor.run {
                execute(image, nodeConfiguration.setNodeName("android/camera_view"))
                execute(virtual_joystick, nodeConfiguration.setNodeName("android/virtual_joystick"))
            }
        } catch (e: IOException) {
            // Socket problem
        }
    }

    private fun getRemap(id: Int) = masterNameSpace.resolve(remaps.get(getString(id))).toString()

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
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

package edu.umontreal.duckietownrc.detection.env

import android.graphics.Bitmap
import android.text.TextUtils
import java.io.Serializable
import java.util.*

/** Size class independent of a Camera object.  */
class Size(val width: Int, val height: Int) : Comparable<Size>, Serializable {
    override fun compareTo(other: Size) = width * height - other.width * other.height

    override fun equals(other: Any?): Boolean {
        if (other == null) return false

        if (other !is Size) return false

        val otherSize = other as Size?
        return width == otherSize!!.width && height == otherSize.height
    }

    override fun hashCode() = width * 32713 + height

    override fun toString() = "$width x $height"
}

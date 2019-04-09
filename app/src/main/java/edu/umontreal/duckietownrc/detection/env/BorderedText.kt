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

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Paint.Style
import android.graphics.Typeface

/** A class that encapsulates the tedious bits of rendering legible, bordered text onto a canvas.  */
class BorderedText(interiorColor: Int, exteriorColor: Int, val textSize: Float) {
    private val interiorPaint: Paint = Paint().apply {
        textSize = this@BorderedText.textSize
        color = interiorColor
        style = Style.FILL
        isAntiAlias = false
        alpha = 255

    }

    private val exteriorPaint: Paint = Paint().apply {
        textSize = this@BorderedText.textSize
        color = exteriorColor
        style = Style.FILL_AND_STROKE
        strokeWidth = this@BorderedText.textSize / 8
        isAntiAlias = false
        alpha = 255
    }

    /**
     * Creates a left-aligned bordered text object with a white interior, and a black exterior with
     * the specified text size.
     *
     * @param textSize text size in pixels
     */
    constructor(textSize: Float) : this(Color.WHITE, Color.BLACK, textSize)

    fun setTypeface(typeface: Typeface) {
        interiorPaint.typeface = typeface
        exteriorPaint.typeface = typeface
    }

    fun drawText(canvas: Canvas, posX: Float, posY: Float, text: String) {
        canvas.drawText(text, posX, posY, exteriorPaint)
        canvas.drawText(text, posX, posY, interiorPaint)
    }

    fun drawText(canvas: Canvas, posX: Float, posY: Float, text: String, bgPaint: Paint) {
        val width = exteriorPaint.measureText(text)
        val textSize = exteriorPaint.textSize
        val paint = Paint(bgPaint)
        paint.style = Style.FILL
        paint.alpha = 160
        canvas.drawRect(posX, posY + textSize.toInt(), posX + width.toInt(), posY, paint)
        canvas.drawText(text, posX, posY + textSize, interiorPaint)
    }
}

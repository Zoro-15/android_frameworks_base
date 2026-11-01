/*
 * Copyright (C) 2019 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package com.android.settingslib.graph

import android.content.Context
import android.graphics.BlendMode
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import java.io.File
import android.util.PathParser
import android.util.TypedValue

import com.android.settingslib.R
import com.android.settingslib.Utils

/**
 * A battery meter drawable that respects paths configured in
 * frameworks/base/core/res/res/values/config.xml to allow for an easily overrideable battery icon
 */
open class ThemedBatteryDrawable(private val context: Context, frameColor: Int) : Drawable() {

    // Need to load:
    // 1. perimeter shape
    // 2. fill mask (if smaller than perimeter, this would create a fill that
    //    doesn't touch the walls
    private val perimeterPath = Path()
    private val scaledPerimeter = Path()
    private val errorPerimeterPath = Path()
    private val scaledErrorPerimeter = Path()
    // Fill will cover the whole bounding rect of the fillMask, and be masked by the path
    private val fillMask = Path()
    private val scaledFill = Path()
    // Based off of the mask, the fill will interpolate across this space
    private val fillRect = RectF()
    // Top of this rect changes based on level, 100% == fillRect
    private val levelRect = RectF()
    private val levelPath = Path()
    // Updates the transform of the paths when our bounds change
    private val scaleMatrix = Matrix()
    private val padding = Rect()
    // The net result of fill + perimeter paths
    private val unifiedPath = Path()

    // Bolt path (used while charging)
    private val boltPath = Path()
    private val scaledBolt = Path()

    // Plus sign (used for power save mode)
    private val plusPath = Path()
    private val scaledPlus = Path()

    private var intrinsicHeight: Int
    private var intrinsicWidth: Int

    // To implement hysteresis, keep track of the need to invert the interior icon of the battery
    private var invertFillIcon = false

    // Colors can be configured based on battery level (see res/values/arrays.xml)
    private var colorLevels: IntArray

    private var fillColor: Int = Color.MAGENTA
    private var backgroundColor: Int = Color.MAGENTA
    // updated whenever level changes
    private var levelColor: Int = Color.MAGENTA

    // Dual tone implies that battery level is a clipped overlay over top of the whole shape
    private var dualTone = false

    private var batteryLevel = 0

    private val invalidateRunnable: () -> Unit = {
        invalidateSelf()
    }

    open var criticalLevel: Int = context.resources.getInteger(
            com.android.internal.R.integer.config_criticalBatteryWarningLevel)

    var charging = false
        set(value) {
            field = value
            postInvalidate()
        }

    var powerSaveEnabled = false
        set(value) {
            field = value
            postInvalidate()
        }

    var showPercent = false
        set(value) {
            field = value
            postInvalidate()
        }

    private val fillColorStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).also { p ->
        p.color = frameColor
        p.isDither = true
        p.strokeWidth = 5f
        p.style = Paint.Style.STROKE
        p.blendMode = BlendMode.SRC
        p.strokeMiter = 5f
        p.strokeJoin = Paint.Join.ROUND
    }

    private val fillColorStrokeProtection = Paint(Paint.ANTI_ALIAS_FLAG).also { p ->
        p.isDither = true
        p.strokeWidth = 5f
        p.style = Paint.Style.STROKE
        p.blendMode = BlendMode.CLEAR
        p.strokeMiter = 5f
        p.strokeJoin = Paint.Join.ROUND
    }

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).also { p ->
        p.color = frameColor
        p.alpha = 255
        p.isDither = true
        p.strokeWidth = 0f
        p.style = Paint.Style.FILL_AND_STROKE
    }

    private val errorPaint = Paint(Paint.ANTI_ALIAS_FLAG).also { p ->
        p.color = Utils.getColorStateListDefaultColor(context, R.color.batterymeter_plus_color)
        p.alpha = 255
        p.isDither = true
        p.strokeWidth = 0f
        p.style = Paint.Style.FILL_AND_STROKE
        p.blendMode = BlendMode.SRC
    }

    // Only used if dualTone is set to true
    private val dualToneBackgroundFill = Paint(Paint.ANTI_ALIAS_FLAG).also { p ->
        p.color = frameColor
        p.alpha = 255
        p.isDither = true
        p.strokeWidth = 0f
        p.style = Paint.Style.FILL_AND_STROKE
    }

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).also { p ->
        p.textAlign = Paint.Align.CENTER
        p.isDither = true
        val fontFile = File("/system/fonts/GoogleSansRounded-Bold.ttf")
        p.typeface = if (fontFile.exists()) {
            try { Typeface.createFromFile(fontFile) } catch (e: Exception) { Typeface.create("sans-serif", Typeface.BOLD) }
        } else {
            Typeface.create("sans-serif", Typeface.BOLD)
        }
        p.isFakeBoldText = false
    }

    init {
        val density = context.resources.displayMetrics.density
        intrinsicHeight = (Companion.HEIGHT * density).toInt()
        intrinsicWidth = (Companion.WIDTH * density).toInt()

        val res = context.resources
        val levels = res.obtainTypedArray(R.array.batterymeter_color_levels)
        val colors = res.obtainTypedArray(R.array.batterymeter_color_values)
        val N = levels.length()
        colorLevels = IntArray(2 * N)
        for (i in 0 until N) {
            colorLevels[2 * i] = levels.getInt(i, 0)
            if (colors.getType(i) == TypedValue.TYPE_ATTRIBUTE) {
                colorLevels[2 * i + 1] = Utils.getColorAttrDefaultColor(context,
                        colors.getThemeAttributeId(i, 0))
            } else {
                colorLevels[2 * i + 1] = colors.getColor(i, 0)
            }
        }
        levels.recycle()
        colors.recycle()

        loadPaths()
    }

    override fun draw(c: Canvas) {
        val b = bounds
        val scale = b.bottom / 15f
        val isDarkTheme = (fillColor and 0x007fffff) > 0x00400000

        // Determine colors dynamically
        val activeFillColor: Int
        val activeTextColor: Int
        val boltColor: Int
        if (charging) {
            activeFillColor = 0xFF34A853.toInt() // Authentic Google Material Green 500
            activeTextColor = 0xFF383E42.toInt() // Shade 1 Refined Charcoal (Android 16 QPR2)
            boltColor = if (isDarkTheme) Color.WHITE else 0xFF383E42.toInt()
        } else if (batteryLevel <= 15) {
            activeFillColor = 0xFFFF334B.toInt() // Red
            activeTextColor = Color.WHITE
            boltColor = 0
        } else if (batteryLevel <= 20) {
            activeFillColor = 0xFFFFCC00.toInt() // Yellow
            activeTextColor = 0xFF383E42.toInt() // Shade 1 Refined Charcoal
            boltColor = 0
        } else {
            if (isDarkTheme) {
                activeFillColor = Color.WHITE
                activeTextColor = 0xFF383E42.toInt() // Shade 1 Refined Charcoal
            } else {
                activeFillColor = fillColor
                activeTextColor = Color.WHITE
            }
            boltColor = 0
        }
        fillPaint.color = activeFillColor
        textPaint.color = activeTextColor

        // 1. Frosted track pill body: (0.5, 1.0) to (25.5, 14.0), radius 5.2
        val pillLeft = 6.5f * scale
        val pillTop = 1.0f * scale
        val pillRight = 31.5f * scale
        val pillBottom = 14.0f * scale
        val pillRadius = 4.4f * scale

        levelRect.set(pillLeft, pillTop, pillRight, pillBottom)
        unifiedPath.reset()
        unifiedPath.addRoundRect(levelRect, pillRadius, pillRadius, Path.Direction.CCW)
        c.drawPath(unifiedPath, dualToneBackgroundFill)

        // 2. Active fill
        if (batteryLevel > 0) {
            val fillFraction = batteryLevel / 100f
            val fillWidth = Math.max(1.5f, 25.0f * fillFraction) * scale
            val fillRight = pillLeft + fillWidth
            c.save()
            c.clipPath(unifiedPath)
            levelRect.set(pillLeft, pillTop, fillRight, pillBottom)
            c.drawRect(levelRect, fillPaint)
            c.restore()
        }

        // 3. Right side indicator: Authentic Thunderbolt (scaled to 80% around center 25.5, 7.5)
        if (charging) {
            scaledBolt.reset()
            scaledBolt.moveTo(29.9f * scale, 11.5f * scale)
            scaledBolt.lineTo(30.3f * scale, 8.7f * scale)
            scaledBolt.lineTo(28.3f * scale, 8.7f * scale)
            scaledBolt.lineTo(31.9f * scale, 3.5f * scale)
            scaledBolt.lineTo(32.7f * scale, 3.5f * scale)
            scaledBolt.lineTo(32.3f * scale, 6.7f * scale)
            scaledBolt.lineTo(34.7f * scale, 6.7f * scale)
            scaledBolt.lineTo(30.7f * scale, 11.5f * scale)
            scaledBolt.close()
            fillPaint.color = boltColor
            c.drawPath(scaledBolt, fillPaint)
        } else {
            // Stadium terminal cap: (32.2, 5.0) to (33.7, 10.0), radius 0.75 (transparent frosted)
            levelRect.set(32.2f * scale, 5.0f * scale, 33.7f * scale, 10.0f * scale)
            c.drawRoundRect(levelRect, 0.75f * scale, 0.75f * scale, dualToneBackgroundFill)
        }

        // 4. Centered Large Bold Text (Google Sans Rounded Bold)
        textPaint.textSize = (if (batteryLevel == 100) 9.8f else 11.0f) * scale
        val textX = 18.50f * scale
        val fm = textPaint.fontMetrics
        val textY = 7.50f * scale - (fm.descent + fm.ascent) / 2f
        c.drawText(batteryLevel.toString(), textX, textY, textPaint)
    }

    private fun batteryColorForLevel(level: Int): Int {
        return when {
            charging || powerSaveEnabled -> fillColor
            else -> getColorForLevel(level)
        }
    }

    private fun getColorForLevel(level: Int): Int {
        var thresh: Int
        var color = 0
        var i = 0
        while (i < colorLevels.size) {
            thresh = colorLevels[i]
            color = colorLevels[i + 1]
            if (level <= thresh) {

                // Respect tinting for "normal" level
                return if (i == colorLevels.size - 2) {
                    fillColor
                } else {
                    color
                }
            }
            i += 2
        }
        return color
    }

    /**
     * Alpha is unused internally, and should be defined in the colors passed to {@link setColors}.
     * Further, setting an alpha for a dual tone battery meter doesn't make sense without bounds
     * defining the minimum background fill alpha. This is because fill + background must be equal
     * to the net alpha passed in here.
     */
    override fun setAlpha(alpha: Int) {
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        fillPaint.colorFilter = colorFilter
        fillColorStrokePaint.colorFilter = colorFilter
        dualToneBackgroundFill.colorFilter = colorFilter
    }

    /**
     * Deprecated, but required by Drawable
     */
    override fun getOpacity(): Int {
        return PixelFormat.OPAQUE
    }

    override fun getIntrinsicHeight(): Int {
        return intrinsicHeight
    }

    override fun getIntrinsicWidth(): Int {
        return intrinsicWidth
    }

    /**
     * Set the fill level
     */
    public open fun setBatteryLevel(l: Int) {
        invertFillIcon = if (l >= 67) true else if (l <= 33) false else invertFillIcon
        batteryLevel = l
        levelColor = batteryColorForLevel(batteryLevel)
        invalidateSelf()
    }

    public fun getBatteryLevel(): Int {
        return batteryLevel
    }

    override fun onBoundsChange(bounds: Rect?) {
        super.onBoundsChange(bounds)
        updateSize()
    }

    fun setPadding(left: Int, top: Int, right: Int, bottom: Int) {
        padding.left = left
        padding.top = top
        padding.right = right
        padding.bottom = bottom

        updateSize()
    }

    fun setColors(fgColor: Int, bgColor: Int, singleToneColor: Int) {
        fillColor = fgColor
        backgroundColor = bgColor
        val isDarkTheme = (fillColor and 0x007fffff) > 0x00400000
        if (isDarkTheme) {
            dualToneBackgroundFill.color = Color.WHITE
            dualToneBackgroundFill.alpha = 0x66
        } else {
            dualToneBackgroundFill.color = fillColor
            dualToneBackgroundFill.alpha = 0x4D
        }
        fillPaint.color = fillColor
        levelColor = batteryColorForLevel(batteryLevel)
        invalidateSelf()
    }

    private fun postInvalidate() {
        unscheduleSelf(invalidateRunnable)
        scheduleSelf(invalidateRunnable, 0)
    }

    private fun updateSize() {
        val b = bounds
        if (b.isEmpty) {
            scaleMatrix.setScale(1f, 1f)
        } else {
            scaleMatrix.setScale((b.right / WIDTH), (b.bottom / HEIGHT))
        }

        perimeterPath.transform(scaleMatrix, scaledPerimeter)
        errorPerimeterPath.transform(scaleMatrix, scaledErrorPerimeter)
        fillMask.transform(scaleMatrix, scaledFill)
        scaledFill.computeBounds(fillRect, true)
        boltPath.transform(scaleMatrix, scaledBolt)
        plusPath.transform(scaleMatrix, scaledPlus)

        // It is expected that this view only ever scale by the same factor in each dimension, so
        // just pick one to scale the strokeWidths
        val scaledStrokeWidth =
                Math.max(b.right / WIDTH * PROTECTION_STROKE_WIDTH, PROTECTION_MIN_STROKE_WIDTH)

        fillColorStrokePaint.strokeWidth = scaledStrokeWidth
        fillColorStrokeProtection.strokeWidth = scaledStrokeWidth
    }

    private fun loadPaths() {
        val pathString = context.resources.getString(
                com.android.internal.R.string.config_batterymeterPerimeterPath)
        perimeterPath.set(PathParser.createPathFromPathData(pathString))
        perimeterPath.computeBounds(RectF(), true)

        val errorPathString = context.resources.getString(
                com.android.internal.R.string.config_batterymeterErrorPerimeterPath)
        errorPerimeterPath.set(PathParser.createPathFromPathData(errorPathString))
        errorPerimeterPath.computeBounds(RectF(), true)

        val fillMaskString = context.resources.getString(
                com.android.internal.R.string.config_batterymeterFillMask)
        fillMask.set(PathParser.createPathFromPathData(fillMaskString))
        // Set the fill rect so we can calculate the fill properly
        fillMask.computeBounds(fillRect, true)

        val boltPathString = context.resources.getString(
                com.android.internal.R.string.config_batterymeterBoltPath)
        boltPath.set(PathParser.createPathFromPathData(boltPathString))

        val plusPathString = context.resources.getString(
                com.android.internal.R.string.config_batterymeterPowersavePath)
        plusPath.set(PathParser.createPathFromPathData(plusPathString))

        dualTone = context.resources.getBoolean(
                com.android.internal.R.bool.config_batterymeterDualTone)
    }

    companion object {
        private const val TAG = "ThemedBatteryDrawable"
        private const val WIDTH = 35f
        private const val HEIGHT = 15f
        private const val CRITICAL_LEVEL = 15
        // On a 12x20 grid, how wide to make the fill protection stroke.
        // Scales when our size changes
        private const val PROTECTION_STROKE_WIDTH = 3f
        // Arbitrarily chosen for visibility at small sizes
        private const val PROTECTION_MIN_STROKE_WIDTH = 6f
    }
}

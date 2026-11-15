/*
 * Copyright (C) 2026 MikuUI
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.systemui.miku.glass

import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.os.SystemProperties

/**
 * MikuUI "glass" look for the notification shade: a neutral tint over the SurfaceFlinger blur,
 * translucent tiles / cards and a thin top-lit rim.
 *
 * Every value is read once when SystemUI starts and can be overridden with
 * `persist.sysui.miku_glass.<name>` (restart SystemUI to apply). `enable=false` restores the
 * stock AOSP colors everywhere.
 */
object MikuGlass {
    private const val PREFIX = "persist.sysui.miku_glass."

    @JvmField val enabled: Boolean = SystemProperties.getBoolean(PREFIX + "enable", true)

    // Shade background tint (drawn over the blurred backdrop).
    private val shadeAlphaDark = prop("shade_alpha_dark", 0.62f)
    private val shadeAlphaLight = prop("shade_alpha_light", 0.50f)
    private val scrimAlphaDark = prop("scrim_alpha_dark", 0.25f)
    private val scrimAlphaLight = prop("scrim_alpha_light", 0.04f)
    private val notifPanelAlpha = prop("notif_panel_alpha", 0.0f)

    // Translucent fill used by tiles, slider tracks and notification cards.
    private val fillAlphaDark = prop("fill_alpha_dark", 0.14f)
    private val fillAlphaLight = prop("fill_alpha_light", 0.50f)

    // Rim highlight: white, fading from top to bottom.
    @JvmField val rimTopAlpha: Float = prop("rim_top", 0.35f)
    @JvmField val rimBottomAlpha: Float = prop("rim_bottom", 0.06f)
    @JvmField val rimWidthDp: Float = prop("rim_width_dp", 1.0f, max = 3f)

    private val NEUTRAL_DARK = Color.rgb(0x1C, 0x1C, 0x1E)
    private val NEUTRAL_LIGHT = Color.rgb(0xF4, 0xF4, 0xF6)

    private fun prop(name: String, fallback: Float, max: Float = 1f): Float =
        SystemProperties.get(PREFIX + name, "").toFloatOrNull()?.coerceIn(0f, max) ?: fallback

    private fun withAlpha(rgb: Int, alpha: Float): Int =
        Color.argb((alpha * 255f + 0.5f).toInt().coerceIn(0, 255), Color.red(rgb), Color.green(rgb),
            Color.blue(rgb))

    @JvmStatic
    fun isNight(context: Context): Boolean =
        (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES

    /** Fill alpha for translucent surfaces on a dark (night) or light shade. */
    @JvmStatic
    fun fillAlpha(night: Boolean): Float = if (night) fillAlphaDark else fillAlphaLight

    /** Neutral tint of the shade panel itself. */
    @JvmStatic
    fun shadePanelColor(context: Context): Int {
        val night = isNight(context)
        return withAlpha(
            if (night) NEUTRAL_DARK else NEUTRAL_LIGHT,
            if (night) shadeAlphaDark else shadeAlphaLight,
        )
    }

    /** Darkening scrim behind the shade panel; keeps text readable on bright wallpapers. */
    @JvmStatic
    fun shadeScrimColor(context: Context): Int =
        withAlpha(Color.BLACK, if (isNight(context)) scrimAlphaDark else scrimAlphaLight)

    /** Panel behind the notification list; mostly transparent so cards float on the glass. */
    @JvmStatic
    fun notificationPanelColor(context: Context): Int =
        withAlpha(if (isNight(context)) NEUTRAL_DARK else NEUTRAL_LIGHT, notifPanelAlpha)

    /** Translucent white fill for a notification card. */
    @JvmStatic
    fun notificationCardColor(context: Context): Int =
        withAlpha(Color.WHITE, fillAlpha(isNight(context)))

    private val rimPath = Path()
    private val rimRect = RectF()
    private val rimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }

    /**
     * Draws the rim inside the rounded rect [left, top, right, bottom] with the given 8-value
     * corner radii. UI thread only (shares a Path/Paint).
     */
    @JvmStatic
    fun drawRim(
        canvas: Canvas,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        radii: FloatArray,
        density: Float,
    ) {
        if (!enabled || rimWidthDp <= 0f || right <= left || bottom <= top) return
        val width = rimWidthDp * density
        rimRect.set(left, top, right, bottom)
        rimPath.rewind()
        rimPath.addRoundRect(rimRect, radii, Path.Direction.CW)
        rimPaint.strokeWidth = width * 2f
        rimPaint.shader =
            LinearGradient(
                0f,
                top,
                0f,
                bottom,
                withAlpha(Color.WHITE, rimTopAlpha),
                withAlpha(Color.WHITE, rimBottomAlpha),
                Shader.TileMode.CLAMP,
            )
        val save = canvas.save()
        canvas.clipPath(rimPath)
        canvas.drawPath(rimPath, rimPaint)
        canvas.restoreToCount(save)
    }
}

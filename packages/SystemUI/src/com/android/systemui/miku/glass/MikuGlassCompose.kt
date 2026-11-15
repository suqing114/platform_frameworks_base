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

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SliderColors
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.dp

/** Whether the current Compose theme is a dark one (glass uses a different fill there). */
@Composable
@ReadOnlyComposable
fun mikuGlassIsNight(): Boolean = MaterialTheme.colorScheme.surface.luminance() < 0.5f

/** Translucent white fill for glass surfaces in the current theme. */
@Composable
@ReadOnlyComposable
fun mikuGlassFill(): Color = Color.White.copy(alpha = MikuGlass.fillAlpha(mikuGlassIsNight()))

/**
 * Draws a thin top-lit rim inside [shape] on top of the content. Clips itself, so it works with
 * or without an outer clip.
 */
fun Modifier.mikuGlassRim(shape: Shape): Modifier {
    if (!MikuGlass.enabled || MikuGlass.rimWidthDp <= 0f) return this
    return drawWithCache {
        val path =
            when (val outline = shape.createOutline(size, layoutDirection, this)) {
                is Outline.Rectangle -> Path().apply { addRect(outline.rect) }
                is Outline.Rounded -> Path().apply { addRoundRect(outline.roundRect) }
                is Outline.Generic -> outline.path
            }
        val brush =
            Brush.verticalGradient(
                0f to Color.White.copy(alpha = MikuGlass.rimTopAlpha),
                1f to Color.White.copy(alpha = MikuGlass.rimBottomAlpha),
            )
        val stroke = Stroke(width = MikuGlass.rimWidthDp.dp.toPx() * 2f)
        onDrawWithContent {
            drawContent()
            clipPath(path) { drawPath(path, brush, style = stroke) }
        }
    }
}

/** Glass variant of the shade slider colors (brightness). */
@Composable
@ReadOnlyComposable
fun mikuGlassSliderColors(base: SliderColors): SliderColors {
    if (!MikuGlass.enabled) return base
    val night = mikuGlassIsNight()
    return base.copy(
        thumbColor = if (night) Color.White else base.thumbColor,
        activeTrackColor = if (night) Color.White else base.activeTrackColor,
        inactiveTrackColor = mikuGlassFill(),
        activeTickColor = if (night) MaterialTheme.colorScheme.primary else base.activeTickColor,
    )
}

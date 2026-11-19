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

package com.android.systemui.miku.hun

import android.app.ActivityOptions
import android.app.WindowConfiguration.WINDOWING_MODE_FREEFORM
import android.content.Context
import android.graphics.Rect
import android.os.SystemProperties
import android.provider.Settings
import android.util.Log
import android.view.WindowManager
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Miku: "pull a heads-up notification down to open it in a small window".
 *
 * Shared helpers for [com.android.systemui.statusbar.notification.headsup.HeadsUpTouchHelper]
 * (gesture) and StatusBarNotificationActivityStarter (launch).
 */
object MikuHunFreeform {
    const val TAG = "MikuHunFreeform"

    /** Settings.Secure key, 1 = enabled (default), 0 = disabled. Toggled from MikuSettings. */
    const val SETTING_KEY = "miku_hun_pull_to_freeform"

    /** Finger travel (after touch slop) needed to arm the launch, in dp. */
    const val THRESHOLD_DP = 64f

    /** Downward fling speed that also arms the launch once half the threshold is passed. */
    const val FLING_DP_PER_SEC = 1200f

    /**
     * true (default): open in an app bubble (floats over the current app, movable / resizable);
     * false: freeform launch, which desktop windowing turns into a desk session.
     */
    @JvmStatic
    fun useBubble(): Boolean =
        SystemProperties.get("persist.sysui.miku_hun_freeform.mode", "bubble") != "freeform"

    @JvmStatic
    fun isEnabled(context: Context, userId: Int): Boolean =
        Settings.Secure.getIntForUser(context.contentResolver, SETTING_KEY, 1, userId) != 0

    /**
     * Rubber-band mapping from finger travel to the row's visual offset: follows at 60% until
     * the threshold, then at 25%.
     */
    @JvmStatic
    fun dampen(dy: Float, threshold: Float): Float =
        when {
            dy <= 0f -> 0f
            dy <= threshold -> dy * 0.6f
            else -> threshold * 0.6f + (dy - threshold) * 0.25f
        }

    /**
     * Options that ask WM to start the notification's activity in a freeform window, sized like
     * a phone and centred on [displayId].
     */
    @JvmStatic
    fun makeLaunchOptions(context: Context, displayId: Int): ActivityOptions {
        val options = ActivityOptions.makeBasic()
        options.launchWindowingMode = WINDOWING_MODE_FREEFORM
        options.launchDisplayId = displayId
        options.setPendingIntentBackgroundActivityStartMode(
            ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED
        )
        if (SystemProperties.getBoolean("persist.sysui.miku_hun_freeform.bounds", true)) {
            launchBounds(context)?.let { options.launchBounds = it }
        }
        return options
    }

    private fun launchBounds(context: Context): Rect? {
        val wm = context.getSystemService(WindowManager::class.java) ?: return null
        val display = wm.maximumWindowMetrics.bounds
        val dw = display.width()
        val dh = display.height()
        if (dw <= 0 || dh <= 0) return null
        // Phone-like 9:16 window: 72% of the display height, never wider than 80% of it.
        var h = dh * 0.72f
        var w = h * 9f / 16f
        if (w > dw * 0.8f) {
            w = dw * 0.8f
            h = min(dh * 0.9f, w * 16f / 9f)
        }
        val left = display.left + ((dw - w) / 2f).roundToInt()
        val top = display.top + ((dh - h) / 2f).roundToInt()
        val bounds = Rect(left, top, left + w.roundToInt(), top + h.roundToInt())
        Log.d(TAG, "launch bounds $bounds in $display")
        return bounds
    }
}

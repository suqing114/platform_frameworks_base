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

package com.android.internal.miku;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.Activity;
import android.util.Log;

/**
 * Hooks from {@link Activity} into MikuUI parallel view, which lives in the
 * {@code androidx.window.extensions} library and registers itself here when it is installed in
 * an app process.
 *
 * <p>Parallel view puts apps that were never designed for Activity Embedding into a split. Two
 * things then go wrong in those apps:
 * <ul>
 *   <li>They see {@link Activity#isInMultiWindowMode()} = true and switch off features meant for
 *       real multi-window, for example Bilibili refuses fullscreen playback.</li>
 *   <li>A video going fullscreen asks for landscape, but its pane stays a pane.</li>
 * </ul>
 * The first is answered by reporting single-window for activities in a parallel-view split, the
 * second by telling parallel view about orientation requests so it can expand the split while
 * the request is landscape and restore it afterwards.
 *
 * @hide
 */
public final class ParallelViewHooks {

    private static final String TAG = "ParallelViewHooks";

    /** Implemented by parallel view. Called on the thread that called into {@link Activity}. */
    public interface Callbacks {
        /** Whether the activity sits in a parallel-view split and should look single-window. */
        boolean shouldReportSingleWindow(@NonNull Activity activity);

        /** The activity called {@link Activity#setRequestedOrientation(int)}. */
        void onRequestedOrientationChanged(@NonNull Activity activity, int requestedOrientation);
    }

    @Nullable
    private static volatile Callbacks sCallbacks;

    private ParallelViewHooks() {}

    public static void setCallbacks(@Nullable Callbacks callbacks) {
        sCallbacks = callbacks;
    }

    /** See {@link Callbacks#shouldReportSingleWindow}. False when parallel view is not active. */
    public static boolean shouldReportSingleWindow(@NonNull Activity activity) {
        final Callbacks callbacks = sCallbacks;
        if (callbacks == null) {
            return false;
        }
        try {
            return callbacks.shouldReportSingleWindow(activity);
        } catch (RuntimeException e) {
            Log.w(TAG, "shouldReportSingleWindow failed", e);
            return false;
        }
    }

    /** See {@link Callbacks#onRequestedOrientationChanged}. */
    public static void onRequestedOrientationChanged(@NonNull Activity activity,
            int requestedOrientation) {
        final Callbacks callbacks = sCallbacks;
        if (callbacks == null) {
            return;
        }
        try {
            callbacks.onRequestedOrientationChanged(activity, requestedOrientation);
        } catch (RuntimeException e) {
            Log.w(TAG, "onRequestedOrientationChanged failed", e);
        }
    }
}

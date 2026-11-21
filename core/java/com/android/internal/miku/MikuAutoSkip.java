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

import android.app.Activity;
import android.app.Application;
import android.content.ContentResolver;
import android.content.pm.ApplicationInfo;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManagerGlobal;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Random;
import java.util.regex.Pattern;

/**
 * MikuUI: clicks the "skip" button of splash ads for the app it runs in.
 *
 * <p>Runs inside the app process (installed by {@code ActivityThread} after
 * {@code Application.onCreate}), so the app sees no accessibility service. When the process goes
 * from no started activity to one (cold start, back from the background, screen back on), it
 * scans all windows of the process for a few seconds and taps a short, visible view whose text
 * or content description reads like "跳过" / "Skip" (with an optional countdown). The tap is a
 * full DOWN/UP touch sequence from the touch screen, so listeners that ignore
 * {@code performClick()} still see it; it repeats while the button stays (countdowns).
 *
 * <p>Settings.Global keys (plain names, readable from app processes):
 * {@link #KEY_ENABLED}; the per-app list is shared with the shake guard
 * ({@link #KEY_DISABLED_APPS}).
 *
 * @hide
 */
public final class MikuAutoSkip implements Application.ActivityLifecycleCallbacks {
    private static final String TAG = "MikuAutoSkip";

    /** Master switch, int, 1 (default) = on. */
    public static final String KEY_ENABLED = "miku_auto_skip_enabled";
    /** Comma separated packages switched off; shared with the shake guard. */
    public static final String KEY_DISABLED_APPS = "miku_shake_guard_disabled_apps";

    private static final long SCAN_WINDOW_MS = 8000;
    private static final long SCAN_INTERVAL_MS = 150;
    private static final long RECLICK_DELAY_MS = 700;
    private static final int MAX_CLICKS = 6;
    private static final int MAX_TEXT_LENGTH = 16;

    // "跳过", "跳過", "Skip", "跳过广告", optionally with a countdown and separators around it:
    // "5s | 跳过", "跳过 3", "3秒 跳过", "Skip Ad >".
    private static final String DECOR = "[\\s\\d:|｜/·•()（）\\[\\]秒sS>›»]*";
    private static final Pattern SKIP = Pattern.compile(
            "^" + DECOR + "(跳过|跳過|skip)(\\s*(广告|廣告|ads?))?" + DECOR + "$",
            Pattern.CASE_INSENSITIVE);

    private final String mPackageName;
    private final ContentResolver mResolver;
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final Random mRandom = new Random();
    private final Rect mRect = new Rect();
    private final int[] mLocation = new int[2];

    private int mStartedActivities;
    private boolean mScanning;
    private long mWindowEnd;
    private long mNextClickAt;
    private int mClicks;
    private int mTouchDeviceId = -2;

    private final Runnable mScan = this::scan;

    private MikuAutoSkip(Application app) {
        mPackageName = app.getPackageName();
        mResolver = app.getContentResolver();
    }

    /** Called by ActivityThread once the Application is created. */
    public static void maybeInstall(Application app, ApplicationInfo ai) {
        if (app == null || !isCandidate(ai)) {
            return;
        }
        app.registerActivityLifecycleCallbacks(new MikuAutoSkip(app));
    }

    /** Third-party apps and updated system apps, but not games (cutscenes have "Skip" too). */
    public static boolean isCandidate(ApplicationInfo ai) {
        if (ai == null || ai.packageName == null) {
            return false;
        }
        if (ai.isSystemApp() && !ai.isUpdatedSystemApp()) {
            return false;
        }
        return ai.category != ApplicationInfo.CATEGORY_GAME
                && (ai.flags & ApplicationInfo.FLAG_IS_GAME) == 0;
    }

    private boolean isEnabled() {
        try {
            if (Settings.Global.getInt(mResolver, KEY_ENABLED, 1) == 0) {
                return false;
            }
            final String disabled = Settings.Global.getString(mResolver, KEY_DISABLED_APPS);
            if (disabled != null) {
                for (String entry : disabled.split(",")) {
                    if (mPackageName.equals(entry.trim())) {
                        return false;
                    }
                }
            }
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    @Override
    public void onActivityStarted(Activity activity) {
        if (mStartedActivities++ == 0) {
            startWindow();
        }
    }

    @Override
    public void onActivityStopped(Activity activity) {
        if (mStartedActivities > 0) {
            mStartedActivities--;
        }
    }

    private void startWindow() {
        if (!isEnabled()) {
            return;
        }
        mWindowEnd = SystemClock.uptimeMillis() + SCAN_WINDOW_MS;
        mClicks = 0;
        mNextClickAt = 0;
        if (!mScanning) {
            mScanning = true;
            mHandler.postDelayed(mScan, SCAN_INTERVAL_MS);
        }
    }

    private void scan() {
        final long now = SystemClock.uptimeMillis();
        if (now > mWindowEnd || mClicks >= MAX_CLICKS || mStartedActivities == 0) {
            mScanning = false;
            return;
        }
        if (now >= mNextClickAt) {
            try {
                final View target = findSkipView();
                if (target != null) {
                    tap(target);
                    mClicks++;
                    mNextClickAt = now + RECLICK_DELAY_MS;
                }
            } catch (Throwable t) {
                Log.w(TAG, "scan failed in " + mPackageName, t);
                mScanning = false;
                return;
            }
        }
        mHandler.postDelayed(mScan, SCAN_INTERVAL_MS);
    }

    private View findSkipView() {
        final ArrayList<View> roots = WindowManagerGlobal.getInstance().getWindowViews();
        // Topmost window first: ads often sit in their own dialog or panel above the activity.
        for (int i = roots.size() - 1; i >= 0; i--) {
            final View root = roots.get(i);
            if (root == null || !root.isAttachedToWindow()
                    || root.getWindowVisibility() != View.VISIBLE
                    || root.getVisibility() != View.VISIBLE) {
                continue;
            }
            final View found = findIn(root, root.getWidth());
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    private View findIn(View view, int rootWidth) {
        if (view.getVisibility() != View.VISIBLE || view.getAlpha() <= 0.05f) {
            return null;
        }
        if (matches(view) && view.isShown() && view.getGlobalVisibleRect(mRect)
                && mRect.width() > 0 && mRect.height() > 0
                && mRect.width() <= rootWidth / 2 && view.isEnabled()) {
            return view;
        }
        if (view instanceof ViewGroup) {
            final ViewGroup group = (ViewGroup) view;
            // Last child first: it is drawn on top.
            for (int i = group.getChildCount() - 1; i >= 0; i--) {
                final View found = findIn(group.getChildAt(i), rootWidth);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private static boolean matches(View view) {
        if (view instanceof TextView && matches(((TextView) view).getText())) {
            return true;
        }
        return matches(view.getContentDescription());
    }

    private static boolean matches(CharSequence text) {
        return text != null && text.length() > 0 && text.length() <= MAX_TEXT_LENGTH
                && SKIP.matcher(text).matches();
    }

    /** Taps the centre of the view's visible part with a DOWN/UP pair from the touch screen. */
    private void tap(View target) {
        final View root = target.getRootView();
        target.getGlobalVisibleRect(mRect); // in root view coordinates
        root.getLocationOnScreen(mLocation);
        final float x = mRect.exactCenterX() + (mRandom.nextFloat() - 0.5f) * mRect.width() * 0.3f;
        final float y = mRect.exactCenterY() + (mRandom.nextFloat() - 0.5f) * mRect.height() * 0.3f;
        final float screenX = x + mLocation[0];
        final float screenY = y + mLocation[1];
        final long downTime = SystemClock.uptimeMillis();
        final float pressure = 0.45f + mRandom.nextFloat() * 0.3f;
        final float size = 0.03f + mRandom.nextFloat() * 0.03f;
        dispatch(root, downTime, downTime, MotionEvent.ACTION_DOWN, screenX, screenY, pressure,
                size);
        final long upDelay = 50 + mRandom.nextInt(45);
        mHandler.postDelayed(() -> {
            if (root.isAttachedToWindow()) {
                dispatch(root, downTime, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP,
                        screenX, screenY, pressure * 0.9f, size);
            }
        }, upDelay);
        final CharSequence label = target instanceof TextView
                ? ((TextView) target).getText() : target.getContentDescription();
        Log.i(TAG, mPackageName + ": tapped \"" + label + "\" (" + target.getClass().getName()
                + ") try " + (mClicks + 1));
    }

    private void dispatch(View root, long downTime, long eventTime, int action, float screenX,
            float screenY, float pressure, float size) {
        final MotionEvent.PointerProperties[] props = { new MotionEvent.PointerProperties() };
        props[0].id = 0;
        props[0].toolType = MotionEvent.TOOL_TYPE_FINGER;
        final MotionEvent.PointerCoords[] coords = { new MotionEvent.PointerCoords() };
        coords[0].x = screenX;
        coords[0].y = screenY;
        coords[0].pressure = pressure;
        coords[0].size = size;
        coords[0].touchMajor = coords[0].touchMinor = 20f + size * 400f;
        final MotionEvent event = MotionEvent.obtain(downTime, eventTime, action, 1, props,
                coords, 0 /* metaState */, 0 /* buttonState */, 1f, 1f, touchDeviceId(),
                0 /* edgeFlags */, InputDevice.SOURCE_TOUCHSCREEN, 0 /* flags */);
        // Raw coordinates stay on screen; the view coordinates become window relative.
        root.getLocationOnScreen(mLocation);
        event.offsetLocation(-mLocation[0], -mLocation[1]);
        root.dispatchTouchEvent(event);
        event.recycle();
    }

    private int touchDeviceId() {
        if (mTouchDeviceId == -2) {
            mTouchDeviceId = 0;
            for (int id : InputDevice.getDeviceIds()) {
                final InputDevice device = InputDevice.getDevice(id);
                if (device != null && device.supportsSource(InputDevice.SOURCE_TOUCHSCREEN)) {
                    mTouchDeviceId = id;
                    break;
                }
            }
        }
        return mTouchDeviceId;
    }

    @Override public void onActivityCreated(Activity activity, Bundle savedInstanceState) {}
    @Override public void onActivityResumed(Activity activity) {}
    @Override public void onActivityPaused(Activity activity) {}
    @Override public void onActivitySaveInstanceState(Activity activity, Bundle outState) {}
    @Override public void onActivityDestroyed(Activity activity) {}
}

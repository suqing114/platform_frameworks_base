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

package com.android.server.sensors;

import android.app.ActivityManager;
import android.app.UidObserver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.Handler;
import android.os.Process;
import android.os.RemoteException;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.Slog;
import android.util.SparseBooleanArray;

import com.android.server.ServiceThread;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * MikuUI "shake guard": for a few seconds after an app comes to the foreground (cold start, back
 * from the background, screen back on), the native SensorService freezes or stills the motion
 * sensor data that app receives, so "shake / twist to open" splash ads cannot fire by accident.
 * If the app enables a motion sensor inside the window, native restarts the window from then
 * (capped at 30 s), since splash ads tend to register their listener late.
 *
 * <p>An app counts as coming to the foreground when its uid enters {@code PROCESS_STATE_TOP}.
 * Only third-party apps and updated system apps are guarded; system uids never are, so the
 * window orientation listener, step counting and the like keep working.
 *
 * <p>Keys are plain Settings.Global names (also written by MikuSettings):
 * <ul>
 * <li>{@code miku_shake_guard_enabled}: int, 1 (default) = on</li>
 * <li>{@code miku_shake_guard_duration_ms}: int, default 5000, 1000..15000</li>
 * <li>{@code miku_shake_guard_disabled_apps}: comma separated packages switched off</li>
 * </ul>
 */
final class MikuShakeGuard {
    private static final String TAG = "MikuShakeGuard";

    static final String KEY_ENABLED = "miku_shake_guard_enabled";
    static final String KEY_DURATION_MS = "miku_shake_guard_duration_ms";
    static final String KEY_DISABLED_APPS = "miku_shake_guard_disabled_apps";

    static final long DEFAULT_DURATION_MS = 5000;
    static final long MIN_DURATION_MS = 1000;
    static final long MAX_DURATION_MS = 15000;

    /** Native side, see {@code com_android_server_sensor_SensorService.cpp}. */
    interface NativeCallback {
        void start(int uid, long durationMs);
        void clear();
    }

    private final Context mContext;
    private final NativeCallback mNative;
    // Own foreground-priority thread: the window must open before the app's first frames, and
    // the shared background thread can be busy with unrelated work.
    private final Handler mHandler;

    // Only touched on mHandler.
    private final SparseBooleanArray mTopUids = new SparseBooleanArray();
    private boolean mEnabled = true;
    private long mDurationMs = DEFAULT_DURATION_MS;
    private Set<String> mDisabledApps = Collections.emptySet();

    MikuShakeGuard(Context context, NativeCallback nativeCallback) {
        mContext = context;
        mNative = nativeCallback;
        final ServiceThread thread = new ServiceThread(TAG,
                Process.THREAD_PRIORITY_FOREGROUND, /* allowIo= */ false);
        thread.start();
        mHandler = new Handler(thread.getLooper());
    }

    /** Call once the activity manager and the settings provider are up. */
    void start() {
        final ContentResolver cr = mContext.getContentResolver();
        final ContentObserver observer = new ContentObserver(mHandler) {
            @Override
            public void onChange(boolean selfChange, Uri uri) {
                loadSettings();
            }
        };
        for (String key : new String[] {KEY_ENABLED, KEY_DURATION_MS, KEY_DISABLED_APPS}) {
            cr.registerContentObserver(Settings.Global.getUriFor(key), false, observer,
                    UserHandle.USER_ALL);
        }
        mHandler.post(this::loadSettings);

        try {
            ActivityManager.getService().registerUidObserver(new UidObserver() {
                @Override
                public void onUidStateChanged(int uid, int procState, long procStateSeq,
                        int capability) {
                    mHandler.post(() -> onProcStateChanged(uid, procState));
                }

                @Override
                public void onUidGone(int uid, boolean disabled) {
                    mHandler.post(() -> mTopUids.delete(uid));
                }
            }, ActivityManager.UID_OBSERVER_PROCSTATE | ActivityManager.UID_OBSERVER_GONE,
                    ActivityManager.PROCESS_STATE_TOP, "android");
        } catch (RemoteException e) {
            Slog.e(TAG, "Could not register the uid observer", e);
        }
    }

    private void loadSettings() {
        final ContentResolver cr = mContext.getContentResolver();
        mEnabled = Settings.Global.getInt(cr, KEY_ENABLED, 1) != 0;
        long duration = Settings.Global.getLong(cr, KEY_DURATION_MS, DEFAULT_DURATION_MS);
        mDurationMs = Math.max(MIN_DURATION_MS, Math.min(MAX_DURATION_MS, duration));
        final Set<String> disabled = new HashSet<>();
        final String value = Settings.Global.getString(cr, KEY_DISABLED_APPS);
        if (value != null) {
            for (String entry : value.split(",")) {
                final String pkg = entry.trim();
                if (!pkg.isEmpty()) {
                    disabled.add(pkg);
                }
            }
        }
        mDisabledApps = disabled;
        if (!mEnabled) {
            mNative.clear();
        }
        Slog.i(TAG, "enabled=" + mEnabled + " durationMs=" + mDurationMs
                + " disabledApps=" + disabled.size());
    }

    private void onProcStateChanged(int uid, int procState) {
        final boolean top = procState != ActivityManager.PROCESS_STATE_UNKNOWN
                && procState <= ActivityManager.PROCESS_STATE_TOP;
        final boolean wasTop = mTopUids.get(uid);
        if (top) {
            mTopUids.put(uid, true);
        } else {
            mTopUids.delete(uid);
        }
        if (!top || wasTop || !mEnabled
                || UserHandle.getAppId(uid) < Process.FIRST_APPLICATION_UID) {
            return;
        }
        final String pkg = findGuardedPackage(uid);
        if (pkg == null) {
            return;
        }
        mNative.start(uid, mDurationMs);
        Slog.i(TAG, "Guarding " + pkg + " (uid " + uid + ") for " + mDurationMs + " ms");
    }

    /** The first package of the uid that is eligible and not switched off, or null. */
    private String findGuardedPackage(int uid) {
        final PackageManager pm = mContext.getPackageManager();
        final String[] packages = pm.getPackagesForUid(uid);
        if (packages == null) {
            return null;
        }
        final int userId = UserHandle.getUserId(uid);
        for (String pkg : packages) {
            if (mDisabledApps.contains(pkg)) {
                continue;
            }
            try {
                final ApplicationInfo ai = pm.getApplicationInfoAsUser(pkg, 0, userId);
                if (isCandidate(ai)) {
                    return pkg;
                }
            } catch (PackageManager.NameNotFoundException e) {
                // Raced with an uninstall.
            }
        }
        return null;
    }

    /** Third-party apps and updated system apps. Keep in sync with MikuSettings. */
    static boolean isCandidate(ApplicationInfo ai) {
        return ai != null && (!ai.isSystemApp() || ai.isUpdatedSystemApp());
    }
}

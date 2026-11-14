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

import android.content.ContentResolver;
import android.content.pm.ApplicationInfo;
import android.provider.Settings;
import android.text.TextUtils;

import java.util.Set;
import java.util.TreeSet;

/**
 * MikuUI "parallel view" (in-app split through Activity Embedding): settings keys and the
 * eligibility rules shared by {@code ActivityThread} and the Settings page.
 *
 * <p>Every eligible app is on by default. The user can switch the feature off as a whole or
 * per app. The keys are plain Settings.Global names on purpose: they are not declared in
 * {@link Settings.Global}, so ordinary apps may read them from their own process.
 *
 * @hide
 */
public final class ParallelViewConfig {

    /** Master switch, int, 1 (default) = on. */
    public static final String KEY_ENABLED = "miku_parallel_view_enabled";

    /**
     * Comma separated packages the user switched off. While unset, {@link #DEFAULT_DISABLED_APPS}
     * applies; the first change from the Settings page writes the full list.
     */
    public static final String KEY_DISABLED_APPS = "miku_parallel_view_disabled_apps";

    /** Share of the width taken by the primary (left) pane, float. */
    public static final String KEY_RATIO = "miku_parallel_view_ratio";

    public static final float DEFAULT_RATIO = 0.38f;
    public static final float MIN_RATIO = 0.15f;
    public static final float MAX_RATIO = 0.85f;

    /** Apps known to work badly when split: full screen lessons, browsers. */
    public static final String DEFAULT_DISABLED_APPS =
            "com.duolingo,com.android.chrome,org.mozilla.firefox,org.mozilla.fennec_fdroid";

    /** Shared library that carries the Activity Embedding implementation. */
    public static final String EXTENSIONS_LIBRARY_FILE = "androidx.window.extensions.jar";

    private ParallelViewConfig() {}

    /**
     * Whether the app may be split at all: third-party apps and updated system apps, but not
     * games. Says nothing about whether the app declared the extensions library.
     */
    public static boolean isCandidate(ApplicationInfo ai) {
        if (ai == null || ai.packageName == null) {
            return false;
        }
        if (ai.isSystemApp() && !ai.isUpdatedSystemApp()) {
            return false;
        }
        if (ai.category == ApplicationInfo.CATEGORY_GAME
                || (ai.flags & ApplicationInfo.FLAG_IS_GAME) != 0) {
            return false;
        }
        return true;
    }

    /**
     * Whether the app has the extensions library on its class path. Needs an ApplicationInfo
     * obtained with {@code PackageManager.GET_SHARED_LIBRARY_FILES}.
     */
    public static boolean declaresExtensions(ApplicationInfo ai) {
        if (ai == null || ai.sharedLibraryFiles == null) {
            return false;
        }
        for (String path : ai.sharedLibraryFiles) {
            if (path != null && path.endsWith("/" + EXTENSIONS_LIBRARY_FILE)) {
                return true;
            }
        }
        return false;
    }

    public static boolean isGloballyEnabled(ContentResolver cr) {
        return Settings.Global.getInt(cr, KEY_ENABLED, 1) != 0;
    }

    public static void setGloballyEnabled(ContentResolver cr, boolean enabled) {
        Settings.Global.putInt(cr, KEY_ENABLED, enabled ? 1 : 0);
    }

    /** Packages switched off, falling back to the defaults while nothing was chosen yet. */
    public static Set<String> getDisabledApps(ContentResolver cr) {
        String value = Settings.Global.getString(cr, KEY_DISABLED_APPS);
        if (value == null) {
            value = DEFAULT_DISABLED_APPS;
        }
        final Set<String> out = new TreeSet<>();
        for (String entry : value.split(",")) {
            final String pkg = entry.trim();
            if (!pkg.isEmpty()) {
                out.add(pkg);
            }
        }
        return out;
    }

    public static boolean isAppEnabled(ContentResolver cr, String packageName) {
        return !getDisabledApps(cr).contains(packageName);
    }

    public static void setAppEnabled(ContentResolver cr, String packageName, boolean enabled) {
        final Set<String> disabled = getDisabledApps(cr);
        if (enabled) {
            disabled.remove(packageName);
        } else {
            disabled.add(packageName);
        }
        Settings.Global.putString(cr, KEY_DISABLED_APPS, TextUtils.join(",", disabled));
    }

    public static float getRatio(ContentResolver cr) {
        return sanitizeRatio(Settings.Global.getFloat(cr, KEY_RATIO, DEFAULT_RATIO));
    }

    public static void setRatio(ContentResolver cr, float ratio) {
        Settings.Global.putFloat(cr, KEY_RATIO, sanitizeRatio(ratio));
    }

    public static float sanitizeRatio(float ratio) {
        if (Float.isNaN(ratio)) {
            return DEFAULT_RATIO;
        }
        return Math.max(MIN_RATIO, Math.min(MAX_RATIO, ratio));
    }
}

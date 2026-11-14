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

package androidx.window.extensions.embedding;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.graphics.Rect;
import android.util.Log;
import android.util.Pair;
import android.view.WindowMetrics;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.window.extensions.WindowExtensions;
import androidx.window.extensions.WindowExtensionsProvider;
import androidx.window.extensions.core.util.function.Predicate;

import com.android.internal.miku.ParallelViewHooks;

import java.util.Collections;

/**
 * MikuUI "parallel view": a system default split rule for apps that never set up Activity
 * Embedding themselves. Activities launched from the task root open in a secondary pane next
 * to it; activities launched from the secondary pane stay there.
 *
 * <p>Called by {@code ActivityThread} (by reflection, through the app class loader) after
 * {@code Application#onCreate} for eligible packages the user did not switch off, see
 * {@code com.android.internal.miku.ParallelViewConfig}.
 *
 * <p>Fullscreen video: apps in a parallel-view split are told they are not in multi-window mode
 * (apps such as Bilibili refuse fullscreen playback in multi-window), and when an activity in
 * the split asks for landscape the split is expanded so that activity covers the whole task.
 * When it asks for anything else again the split goes back to its normal ratio. See
 * {@link ParallelViewHooks}.
 */
public final class MikuParallelView {

    private static final String TAG = "MikuParallelView";

    /** Tag of the rule, visible in dumps. Also identifies parallel-view splits. */
    static final String RULE_TAG = "miku_parallel_view";

    static final float DEFAULT_RATIO = 0.38f;
    private static final float MIN_RATIO = 0.15f;
    private static final float MAX_RATIO = 0.85f;

    /** Only split when the parent window is landscape and at least this wide. */
    private static final float MIN_PARENT_WIDTH_DP = 600f;

    private MikuParallelView() {}

    /**
     * Installs the default rule into this process's {@link SplitController}.
     *
     * @param ratio share of the parent width taken by the primary (left) pane.
     */
    public static void install(float ratio) {
        final WindowExtensions extensions = WindowExtensionsProvider.getWindowExtensions();
        final ActivityEmbeddingComponent component =
                extensions.getActivityEmbeddingComponent();
        if (!(component instanceof SplitController)) {
            Log.i(TAG, "Activity embedding not available, skipped");
            return;
        }
        final SplitController controller = (SplitController) component;
        controller.setSystemEmbeddingRules(Collections.singleton(createRule(ratio)));
        ParallelViewHooks.setCallbacks(new Hooks(controller));
        Log.i(TAG, "Installed, ratio=" + sanitizeRatio(ratio));
    }

    @NonNull
    static SplitPairRule createRule(float ratio) {
        final Predicate<Pair<Activity, Activity>> activityPair = pair ->
                isEligiblePrimary(pair.first)
                        && isOtherActivityOfSameApp(pair.first.getComponentName(),
                                pair.second.getComponentName());
        final Predicate<Pair<Activity, Intent>> activityIntentPair = pair ->
                isEligiblePrimary(pair.first)
                        && isOtherActivityOfSameApp(pair.first.getComponentName(),
                                pair.second.getComponent());
        final Predicate<WindowMetrics> parentMetrics = MikuParallelView::isWideLandscape;

        final SplitAttributes attributes = new SplitAttributes.Builder()
                .setSplitType(new SplitAttributes.SplitType.RatioSplitType(sanitizeRatio(ratio)))
                .build();

        return new SplitPairRule.Builder(activityPair, activityIntentPair, parentMetrics)
                .setDefaultSplitAttributes(attributes)
                // Never take down an activity just because its neighbour finished: the other
                // pane simply expands. This keeps splash-style launchers and back navigation
                // predictable for apps that were never designed for embedding.
                .setFinishPrimaryWithSecondary(SplitRule.FINISH_NEVER)
                .setFinishSecondaryWithPrimary(SplitRule.FINISH_NEVER)
                .setShouldClearTop(false)
                .setTag(RULE_TAG)
                .build();
    }

    /** Only the task root acts as the list pane; launches from the detail pane stay there. */
    private static boolean isEligiblePrimary(@NonNull Activity activity) {
        return !activity.isFinishing() && activity.isTaskRoot();
    }

    /**
     * Embedding only works within one app, so implicit intents and other packages never split.
     * Relaunching the same activity does not split either.
     */
    private static boolean isOtherActivityOfSameApp(@Nullable ComponentName primary,
            @Nullable ComponentName secondary) {
        return primary != null && secondary != null
                && primary.getPackageName().equals(secondary.getPackageName())
                && !primary.getClassName().equals(secondary.getClassName());
    }

    private static boolean isWideLandscape(@NonNull WindowMetrics metrics) {
        final Rect bounds = metrics.getBounds();
        final float density = metrics.getDensity();
        if (density <= 0f || bounds.width() <= bounds.height()) {
            return false;
        }
        return bounds.width() / density >= MIN_PARENT_WIDTH_DP;
    }

    static float sanitizeRatio(float ratio) {
        if (Float.isNaN(ratio)) {
            return DEFAULT_RATIO;
        }
        return Math.max(MIN_RATIO, Math.min(MAX_RATIO, ratio));
    }

    /** Connects {@link android.app.Activity} to this process's split controller. */
    private static final class Hooks implements ParallelViewHooks.Callbacks {
        private final SplitController mController;

        Hooks(@NonNull SplitController controller) {
            mController = controller;
        }

        @Override
        public boolean shouldReportSingleWindow(@NonNull Activity activity) {
            return mController.isInMikuParallelViewSplit(activity);
        }

        @Override
        public void onRequestedOrientationChanged(@NonNull Activity activity,
                int requestedOrientation) {
            // Landscape means a video (or similar) is going fullscreen: let it cover the task.
            // Anything else ends that again. A no-op when the activity is not in a
            // parallel-view split or already is in the wanted state.
            final boolean expand = ActivityInfo.isFixedOrientationLandscape(requestedOrientation);
            if (mController.setMikuParallelViewExpanded(activity, expand)) {
                Log.i(TAG, (expand ? "Expanded for " : "Restored split for ")
                        + activity.getComponentName() + ", orientation="
                        + ActivityInfo.screenOrientationToString(requestedOrientation));
            }
        }
    }
}

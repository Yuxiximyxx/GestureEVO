package io.github.lumiagg.gestureevo.utils

import android.app.Activity
import android.graphics.Color
import androidx.core.view.WindowCompat

/**
 * Global navigation-bar immersion.
 *
 * Applied once per Activity from [io.github.lumiagg.gestureevo.EvoApplication]
 * (its ActivityLifecycleCallbacks run after the Activity's own onCreate, so
 * per-Activity window code still runs first). It only touches the navigation
 * bar: content extends under a transparent navigation bar, while the
 * layouts' existing fitsSystemWindows handling keeps interactive content
 * above the bar. Status-bar colour/handling is left to each Activity.
 *
 * Excluded: QuickSearchActivity manages decorFitsSystemWindows itself — it
 * toggles the flag from its keyboard-follow-animation setting after
 * onCreate, and a blanket override here would break that setting.
 */
object NavigationImmersion {
    private const val QUICK_SEARCH_ACTIVITY =
        "io.github.lumiagg.gestureevo.search.QuickSearchActivity"

    fun shouldApply(activity: Activity): Boolean =
        activity.javaClass.name != QUICK_SEARCH_ACTIVITY

    fun apply(activity: Activity) {
        if (!shouldApply(activity)) return
        WindowCompat.setDecorFitsSystemWindows(activity.window, false)
        activity.window.navigationBarColor = Color.TRANSPARENT
    }
}

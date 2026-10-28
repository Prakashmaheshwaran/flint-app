package com.flint.peakfocus.blocking.overlay

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import com.flint.peakfocus.core.model.EnforcementSignals
import com.flint.peakfocus.core.model.FallbackSurfaceHandoff

/** Application-lifetime adapter: overlay dismissal alone cannot finish an Activity fallback. */
class PathBFallbackActivityHandoff(application: Application) : Application.ActivityLifecycleCallbacks {
    private val main = Handler(Looper.getMainLooper())
    private val policy = FallbackSurfaceHandoff<BlockActivity>()

    init {
        application.registerActivityLifecycleCallbacks(this)
        EnforcementSignals.detectors.subscribe {
            val update = Runnable {
                policy.pathAChanged(EnforcementSignals.detectors.pathAConnected).forEach { activity ->
                    if (!activity.isFinishing && !activity.isDestroyed) activity.finish()
                }
            }
            if (Looper.myLooper() == Looper.getMainLooper()) update.run() else main.post(update)
        }
    }

    override fun onActivityStarted(activity: Activity) {
        if (activity is BlockActivity) policy.started(activity)
    }

    override fun onActivityResumed(activity: Activity) {
        if (activity is BlockActivity) policy.started(activity)
    }

    override fun onActivityStopped(activity: Activity) {
        if (activity is BlockActivity) policy.stopped(activity)
    }

    override fun onActivityDestroyed(activity: Activity) {
        if (activity is BlockActivity) policy.stopped(activity)
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
}

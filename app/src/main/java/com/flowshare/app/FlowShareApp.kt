package com.flowshare.app

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle
import android.provider.Settings
import com.flowshare.app.core.DeviceIdentity
import com.flowshare.app.nearby.NearbyManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class FlowShareApp : Application() {
    lateinit var identity: DeviceIdentity
        private set
    lateinit var nearby: NearbyManager
        private set

    /** True while the full Flow Share screen is in front (it then handles incoming offers itself). */
    private val _mainVisible = MutableStateFlow(false)
    val mainVisible: StateFlow<Boolean> = _mainVisible.asStateFlow()

    override fun onCreate() {
        super.onCreate()
        identity = DeviceIdentity(this)
        nearby = NearbyManager(this, identity)
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: Activity) {
                if (activity is MainActivity) _mainVisible.value = true
            }
            override fun onActivityPaused(activity: Activity) {
                if (activity is MainActivity) _mainVisible.value = false
            }
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityStarted(activity: Activity) = Unit
            override fun onActivityStopped(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
    }
}

/** Small persisted settings. */
class FlowPrefs(context: Context) {
    private val prefs = context.getSharedPreferences("flow_prefs", Context.MODE_PRIVATE)

    /** Keep receiving (and show the overlay) while other apps such as Gallery are open. */
    var receiveAnywhere: Boolean
        get() = prefs.getBoolean("receive_anywhere", false)
        set(v) = prefs.edit().putBoolean("receive_anywhere", v).apply()

    companion object {
        fun canDrawOverlays(context: Context) = Settings.canDrawOverlays(context)
    }
}

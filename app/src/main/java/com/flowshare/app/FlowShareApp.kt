package com.flowshare.app

import android.app.Application
import com.flowshare.app.core.DeviceIdentity
import com.flowshare.app.nearby.NearbyManager

class FlowShareApp : Application() {
    lateinit var identity: DeviceIdentity
        private set
    lateinit var nearby: NearbyManager
        private set

    override fun onCreate() {
        super.onCreate()
        identity = DeviceIdentity(this)
        nearby = NearbyManager(this, identity)
    }
}

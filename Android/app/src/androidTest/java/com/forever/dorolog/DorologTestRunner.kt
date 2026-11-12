package com.forever.dorolog

import android.app.UiAutomation
import android.content.pm.PackageManager
import androidx.test.runner.AndroidJUnitRunner

class DorologTestRunner : AndroidJUnitRunner() {
    override fun onStart() {
        val permissions = buildList {
            add(android.Manifest.permission.RECORD_AUDIO)
            add(android.Manifest.permission.READ_PHONE_STATE)
            if (targetContext.packageManager.hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE)) {
                add("android.car.permission.CAR_SPEED")
            }
        }
        for (permission in permissions) {
            if (targetContext.checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) {
                getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
                    .grantRuntimePermission(targetContext.packageName, permission)
                check(targetContext.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED)
            }
        }
        super.onStart()
    }
}

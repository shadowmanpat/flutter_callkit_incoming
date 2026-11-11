package com.hiennv.flutter_callkit_incoming

/**
 * Where a person who pressed the notification-preferences button may go.
 * Incoming-call creation does not call this. Android 14 is API 34.
 * Android 15 is API 35, which is the Xiaomi crash generation.
 */
object FullScreenIntentPolicy {
    const val android14 = 34
    const val android15 = 35

    const val none = "none"
    const val fullScreenSetting = "full_screen_setting"
    const val appDetails = "app_details"
    const val explainOnly = "explain_only"

    fun choose(
        sdkInt: Int,
        permissionGranted: Boolean,
        fullScreenResolves: Boolean,
        fullScreenLaunchFailed: Boolean,
        detailsResolves: Boolean,
        detailsLaunchFailed: Boolean,
    ): String {
        if (sdkInt < android14 || permissionGranted) return none
        if (fullScreenResolves && !fullScreenLaunchFailed) return fullScreenSetting
        if (detailsResolves && !detailsLaunchFailed) return appDetails
        return explainOnly
    }
}

package ai.pipecat.client.openai_realtime_webrtc

import ai.pipecat.client.result.RTVIError
import android.Manifest
import android.content.Context
import android.content.pm.PackageManager

/**
 * Returns an error if the app is missing the ACCESS_NETWORK_STATE permission.
 *
 * WebRTC's network monitor requires this permission, and aborts the process
 * with an uncatchable native SIGABRT if it is missing, so check upfront and
 * fail gracefully instead.
 */
internal fun checkAccessNetworkStatePermission(context: Context): RTVIError? =
    if (context.checkSelfPermission(Manifest.permission.ACCESS_NETWORK_STATE) ==
        PackageManager.PERMISSION_GRANTED
    ) {
        null
    } else {
        RTVIError.OtherError(
            "This app is missing the android.permission.ACCESS_NETWORK_STATE permission, " +
                "which is required by WebRTC. Please add <uses-permission " +
                "android:name=\"android.permission.ACCESS_NETWORK_STATE\" /> to the app manifest."
        )
    }

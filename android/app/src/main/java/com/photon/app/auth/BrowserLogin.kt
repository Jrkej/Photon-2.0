package com.photon.app.auth

import android.app.Activity
import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent

/**
 * Opens a provider's sign-in page in a Custom Tab (never a WebView). The redirect comes back to
 * MainActivity through `photon://auth/cb` (debug) or the App Link `https://<host>/auth/cb`.
 */
object BrowserLogin {
    fun open(activity: Activity, authorizationUrl: Uri) {
        CustomTabsIntent.Builder().setShowTitle(true).build().launchUrl(activity, authorizationUrl)
    }
}

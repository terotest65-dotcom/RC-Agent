package com.acme.rc.ui

import android.annotation.SuppressLint
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import com.acme.rc.agent.AgentConfig

/**
 * Local WebView onboarding screen. The form is rendered from
 * assets/onboarding.html and talks to native code through a small bridge.
 */
@SuppressLint("SetJavaScriptEnabled", "AddJavascriptInterface")
@Composable
fun OnboardingScreen(
    onSave: (name: String, phone: String) -> Boolean,
    onContinue: () -> Unit,
    onOpenAccessibility: () -> Unit
) {
    val activityContext = LocalContext.current
    val currentSave by rememberUpdatedState(onSave)
    val currentContinue by rememberUpdatedState(onContinue)
    val currentOpenAccessibility by rememberUpdatedState(onOpenAccessibility)
    val mainHandler = remember { Handler(Looper.getMainLooper()) }

    val webView = remember {
        WebView(activityContext).apply {
            setBackgroundColor(Color.rgb(245, 247, 251))
            overScrollMode = View.OVER_SCROLL_NEVER
            isVerticalScrollBarEnabled = false
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = false
            settings.setSupportZoom(false)
            settings.builtInZoomControls = false
            settings.displayZoomControls = false
            webViewClient = WebViewClient()
            addJavascriptInterface(
                OnboardingBridge(
                    loadProfile = {
                        AgentConfig.profileJson(activityContext)?.toString() ?: "{}"
                    },
                    save = { name, phone, agreed ->
                        if (!agreed) false else currentSave(name, phone)
                    },
                    onContinue = {
                        mainHandler.post { currentContinue() }
                    },
                    onOpenAccessibility = {
                        mainHandler.post { currentOpenAccessibility() }
                    }
                ),
                "RCAndroid"
            )
            loadUrl("file:///android_asset/onboarding.html")
        }
    }

    AndroidView(
        factory = { webView },
        modifier = Modifier.fillMaxSize()
    )

    DisposableEffect(Unit) {
        onDispose {
            webView.removeJavascriptInterface("RCAndroid")
            webView.destroy()
        }
    }
}

private class OnboardingBridge(
    private val loadProfile: () -> String,
    private val save: (String, String, Boolean) -> Boolean,
    private val onContinue: () -> Unit,
    private val onOpenAccessibility: () -> Unit
) {
    @JavascriptInterface
    fun getProfile(): String = loadProfile()

    @JavascriptInterface
    fun saveProfile(name: String, phone: String, agreed: Boolean): Boolean =
        save(name.trim(), phone.trim(), agreed)

    @JavascriptInterface
    fun continueToApp() = onContinue()

    @JavascriptInterface
    fun openAccessibility() = onOpenAccessibility()
}

# Keep the WebView bridge methods reachable from JavaScript.
-keepclassmembers class com.acme.rc.MainActivity$Bridge {
    @android.webkit.JavascriptInterface <methods>;
}

# OkHttp ships its own consumer rules. Keep annotation metadata for JSON.
-keepattributes *Annotation*

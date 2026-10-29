# WebView JavaScript bridge must survive R8.
-keepclassmembers class app.simpleinsta.ig.web.Bridge$NativeBridge {
    public *;
}
-keepattributes JavascriptInterface
-keepattributes *Annotation*

# Media3 / ExoPlayer
-dontwarn androidx.media3.**

# WebRTC (JNI)
-keep class org.webrtc.** { *; }
-dontwarn org.webrtc.**
# JS bridge used by the chibi stage WebView
-keepclassmembers class * { @android.webkit.JavascriptInterface <methods>; }
# kotlinx.serialization models
-keepclassmembers @kotlinx.serialization.Serializable class com.daijobu.famichibi.** { *; }
-keep class com.daijobu.famichibi.**$$serializer { *; }
-keepclasseswithmembers class com.daijobu.famichibi.** { kotlinx.serialization.KSerializer serializer(...); }
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

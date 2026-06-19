# The Continental — release shrinking rules
# Media3/ExoPlayer ship their own consumer ProGuard rules, these are extra safety nets.

-keep class androidx.media3.** { *; }
-dontwarn androidx.media3.**

-keepclassmembers class * implements android.os.Parcelable {
    public static final ** CREATOR;
}

-keepattributes *Annotation*, Signature, Exceptions, InnerClasses, EnclosingMethod

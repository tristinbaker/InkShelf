# Audiobookshelf payloads are decoded reflectively by kotlinx.serialization, so
# the generated serializers must survive shrinking.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**

-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.tristinbaker.inkshelf.**$$serializer { *; }
-keepclassmembers class com.tristinbaker.inkshelf.** {
    *** Companion;
}
-keepclasseswithmembers class com.tristinbaker.inkshelf.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# Room generates implementations that are looked up by name.
-keep class * extends androidx.room.RoomDatabase { <init>(); }
-dontwarn androidx.room.paging.**

# Tink, pulled in by androidx.security:security-crypto, references Error Prone
# annotations that are compile-time only and never packaged.
-dontwarn com.google.errorprone.annotations.**

# OkHttp / Okio ship references to optional platform pieces.
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

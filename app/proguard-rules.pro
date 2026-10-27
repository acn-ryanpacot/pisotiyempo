# kotlinx.serialization: keep generated serializers for the API DTOs.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class com.pisotiyempo.data.remote.** {
    *** Companion;
}
-keepclasseswithmembers class com.pisotiyempo.data.remote.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.pisotiyempo.data.remote.**$$serializer { *; }
-keepclassmembers class com.pisotiyempo.ui.chart.** {
    *** Companion;
}
-keepclasseswithmembers class com.pisotiyempo.ui.chart.** {
    kotlinx.serialization.KSerializer serializer(...);
}

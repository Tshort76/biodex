# R8 is off for the release build (see the release block in build.gradle.kts). These rules
# are here so that turning it on is a one-line change rather than a research project.
#
# Room, Compose, OkHttp, Coil and kotlinx-serialization all ship their own consumer rules,
# so the only thing that needs stating is what this app reflects on itself: nothing, apart
# from the serializers the kotlinx-serialization compiler plugin generates.
-keepclassmembers class dev.tlong.biodex.** {
    *** Companion;
}
-keepclasseswithmembers class dev.tlong.biodex.** {
    kotlinx.serialization.KSerializer serializer(...);
}

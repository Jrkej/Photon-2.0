# Wire-generated messages are reached through their ADAPTER fields.
-keep class com.photon.proto.** { *; }
# Cronet is loaded reflectively from Play Services.
-keep class org.chromium.net.** { *; }
-dontwarn org.chromium.net.**

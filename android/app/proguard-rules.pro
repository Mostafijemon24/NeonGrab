# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Uncomment this to preserve the line number information for
# debugging stack traces.
#-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile

-keep class com.yausername.youtubedl_android.** { *; }
-keep class com.yausername.ffmpeg.** { *; }
-keep class com.neongrab.downloader.ytdlp.** { *; }

# Jackson ObjectMapper is used by youtubedl-android for JSON serialization.
# Without explicit keep rules, R8 may rename or remove Jackson classes, causing
# ExceptionInInitializerError when YoutubeDL.INSTANCE is first accessed.
-keep class com.fasterxml.jackson.** { *; }
-keepnames class com.fasterxml.jackson.** { *; }
-dontwarn com.fasterxml.jackson.**

# Apache Commons Compress is used by youtubedl-android's ZipUtils to unpack
# libpython.zip.so and libffmpeg.zip.so into noBackupFilesDir on first launch.
-keep class org.apache.commons.compress.** { *; }
-keep class org.apache.commons.io.** { *; }
-dontwarn org.apache.commons.**

# Kotlin coroutines and stdlib
-dontwarn kotlin.**
-dontwarn kotlinx.**

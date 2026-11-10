# Karaoke release (R8) rules. Media3 / AndroidX ship their own consumer rules.
# NanoHTTPD and ZXing are plain Java without reflection; org.json is part of Android.
-keep class com.setupbox.karaoke.MainActivity { *; }
-keep class com.setupbox.karaoke.KeepAliveService { *; }
# stack traces stay readable in Play Console crash reports
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

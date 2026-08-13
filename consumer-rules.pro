# Keep host-facing API. Internals may be shrunk/obfuscated.
-keep class com.ctvhouse.sdk.SdkVersion { *; }
-keep class com.ctvhouse.sdk.format.Overlay { *; }
-keep class com.ctvhouse.sdk.format.Overlay$* { *; }
-keep class com.ctvhouse.sdk.format.TriggerRoll { *; }
-keep class com.ctvhouse.sdk.format.TriggerRoll$* { *; }
-keep class com.ctvhouse.sdk.manual.PauseRollAd { *; }
-keep class com.ctvhouse.sdk.manual.PauseRollAd$* { *; }
-dontwarn com.google.zxing.**

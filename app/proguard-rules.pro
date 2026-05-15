# Keep Xposed module classes
-keep class asia.nana7mi.arirang.hook.** { *; }

# Keep Xposed API classes
-keep class de.robv.android.xposed.** { *; }
-keepnames class de.robv.android.xposed.** { *; }

# Keep specific UI classes that might be hooked
-keep class asia.nana7mi.arirang.ui.** { *; }

# Keep Gson-backed preference models. Hook code reads these JSON keys by name.
-keepattributes Signature
-keepattributes *Annotation*
-keep class asia.nana7mi.arirang.model.SimInfo { *; }

# Keep BuildConfig (sometimes used in hooks)
-keep class asia.nana7mi.arirang.BuildConfig { *; }

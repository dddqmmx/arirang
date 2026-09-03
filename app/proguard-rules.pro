# Keep Xposed module classes
-keep class asia.nana7mi.arirang.hook.** { *; }

# libxposed rules
-dontwarn io.github.libxposed.annotation.**
-adaptresourcefilecontents META-INF/xposed/java_init.list
-keep,allowoptimization,allowobfuscation public class * extends io.github.libxposed.api.XposedModule {
    public <init>(...);
}
-keep class io.github.libxposed.** { *; }

# XposedActivation resolves this class and its isXposedActivation method by name
# (hook/activation/XposedActivation.kt), so it must survive shrinking. Every other
# ui.** class is reached normally and is kept by AGP's manifest-component rules.
-keep class asia.nana7mi.arirang.ui.fragment.HomeFragment { *; }

# Keep Gson-backed preference models. Hook code reads these JSON keys by name.
-keepattributes Signature
-keepattributes *Annotation*
-keep class asia.nana7mi.arirang.model.SimInfo { *; }

# Keep BuildConfig (sometimes used in hooks)
-keep class asia.nana7mi.arirang.BuildConfig { *; }

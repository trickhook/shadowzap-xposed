# R8 rules for the Shadowzap module APK.

-keepattributes Signature,InnerClasses,EnclosingMethod,Exceptions,*Annotation*,SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# ---- Entry points -------------------------------------------------------------------------------------------------
# Frameworks instantiate entries by name (META-INF/xposed/java_init.list, assets/xposed_init), so names, the
# constructors frameworks look up and the callbacks they invoke must survive unchanged.
-adaptresourcefilecontents META-INF/xposed/java_init.list

# libxposed API 101+ (Vector >= 2.1, JingMatrix LSPatch >= 1.0): public no-arg constructor.
-keep class io.github.trickhook.shadowzap.entry.ModernEntry { *; }

# libxposed API 100 (LSPosed 1.9.x, Vector <= 2.0): the (XposedInterface, ModuleLoadedParam) constructor and the
# API 100 onPackageLoaded. Both reference API 100 shapes that do not exist in the API 102 jar R8 sees; the
# -dontwarn below covers them and R8 keeps the references as they are.
-keep class io.github.trickhook.shadowzap.compat.api100.Api100Entry {
    public <init>(io.github.libxposed.api.XposedInterface, io.github.libxposed.api.XposedModuleInterface$ModuleLoadedParam);
    public void onPackageLoaded(io.github.libxposed.api.XposedModuleInterface$PackageLoadedParam);
}
# Loaded by name from Api100Entry.
-keep interface io.github.trickhook.shadowzap.compat.api100.Api100Boot { *; }
-keep class io.github.trickhook.shadowzap.entry.Api100BootImpl {
    public <init>();
}

# Legacy API (old LSPatch, EdXposed): named in assets/xposed_init, which R8 does not rewrite.
-keep class io.github.trickhook.shadowzap.entry.LegacyEntry { *; }

# Framework callbacks implemented by us.
-keep class * implements io.github.libxposed.api.XposedInterface$Hooker { *; }
-keep class * extends de.robv.android.xposed.XC_MethodHook { *; }

# Framework APIs are provided at runtime by the Xposed implementation.
-dontwarn io.github.libxposed.**
-dontwarn de.robv.android.xposed.**

# ---- kotlinx.serialization -----------------------------------------------------------------------------------------
-keepclassmembers @kotlinx.serialization.Serializable class ** {
    static ** Companion;
    *** Companion;
    static **$* *;
    kotlinx.serialization.KSerializer serializer(...);
}
-keepclasseswithmembers class ** {
    kotlinx.serialization.KSerializer serializer(...);
}
-if @kotlinx.serialization.Serializable class **
-keep class <1>$$serializer { *; }

# ---- coroutines optional dependencies ------------------------------------------------------------------------
-dontwarn kotlinx.coroutines.debug.**
-dontwarn reactor.blockhound.**

# R8 / ProGuard rules for the release build.
#
# Every rule here exists because something looks a name up as a *string* or a *symbol*, which is the
# one thing shrinking cannot see. Nothing here is decoration: each one has a failure attached.

# BuildStamp reads the build identity BY NAME - BuildConfig.class.getField("GIT_COMMIT") - so the
# class and its fields have to survive with their names. Drop this and the About box silently loses
# its commit line: "Version 0.1.0-beta" with no [sha], the failure docs/SESSIONS.md warns about.
-keep class com.pbcnx.opendtvmod.BuildConfig { *; }

# WebView bridges. The JS side calls these methods by name over addJavascriptInterface, so neither
# the method names nor their classes may be renamed.
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
-keep class com.pbcnx.opendtvmod.bml.BmlController { *; }

# Native methods: nextlib's FFmpeg decoders resolve their Java peers and symbols by name. (The vendor
# tuner JNI that used to need this line too was removed on 2026-11-04 with the Sony/Endeavour path.)
-keepclasseswithmembernames,includedescriptorclasses class * {
    native <methods>;
}

# Removed 2026-11-04: "-keep class com.adth.** { *; }" - that kept the Sony/Endeavour vendor driver and
# its three .so files, ~6.4 MB, which only a 9309 could have needed. The clean-room 9308 driver needs
# none of it; nothing in the tree refers to com.adth any more.

# TunerRegistry loads the optional drivers BY NAME - Class.forName(...) over a String[] - so that a
# vendor-free build can simply not have them. R8 cannot see a string: the first minified build had
# no tuner at all, logging "driver not in this build: ...SmsRioProvider" and showing "Tuner not found"
# with the dongle plugged in. Keep the whole provider package by name.
-keep class com.pbcnx.opendtvmod.source.provider.** { *; }
-keepnames class com.pbcnx.opendtvmod.source.provider.** { *; }

# Keep the Activities and Services the manifest names, including the ones only the debug manifest
# declares (the analyzer and the developers screen).
-keep class com.pbcnx.opendtvmod.MainActivity { *; }
-keep class com.pbcnx.opendtvmod.analyzer.AnalyzerActivity { *; }
-keep class com.pbcnx.opendtvmod.dev.screen.DeveloperOptionsActivity { *; }
-keep class com.pbcnx.opendtvmod.LicensesActivity { *; }
-keep class com.pbcnx.opendtvmod.analyzer.UsbProbeActivity { *; }
-keep class com.pbcnx.opendtvmod.tv.OpenDtvInputService { *; }
-keep class com.pbcnx.opendtvmod.tv.SetupActivity { *; }

# Enum valueOf/values by name, used by the prefs and the analyzer's tab constants.
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

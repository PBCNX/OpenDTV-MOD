# R8 / ProGuard rules for the release build.
#
# Every rule here exists because something looks a name up as a *string* or a *symbol*, which is the
# one thing shrinking cannot see. Nothing here is decoration: each one has a failure attached.

# BuildStamp reads the build identity BY NAME - BuildConfig.class.getField("GIT_COMMIT") - so the
# class and its fields have to survive with their names. Drop this and the About box silently loses
# its commit line: "Version 0.1.0-beta" with no [sha], the failure docs/SESSIONS.md warns about.
-keep class ph.opendtv.BuildConfig { *; }

# WebView bridges. The JS side calls these methods by name over addJavascriptInterface, so neither
# the method names nor their classes may be renamed.
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
-keep class ph.opendtv.bml.BmlController { *; }

# Native methods: the vendor tuner JNI (endeavour/sony) and nextlib's FFmpeg decoders resolve their
# Java peers and symbols by name.
-keepclasseswithmembernames,includedescriptorclasses class * {
    native <methods>;
}

# The vendor tuner driver does its own internal lookups and is small; there is nothing to gain.
-keep class com.adth.** { *; }

# TunerRegistry loads the optional drivers BY NAME - Class.forName(...) over a String[] - so that a
# vendor-free build can simply not have them. R8 cannot see a string: the first minified build had
# no tuner at all, logging "driver not in this build: ...SmsRioProvider / ...Sony9308Provider" and
# showing "Tuner not found" with the dongle plugged in. Keep the whole provider package by name.
-keep class ph.opendtv.source.provider.** { *; }
-keepnames class ph.opendtv.source.provider.** { *; }

# Keep the Activities and Services the manifest names, including the ones only the debug manifest
# declares (the analyzer and the developers screen).
-keep class ph.opendtv.MainActivity { *; }
-keep class ph.opendtv.AnalyzerActivity { *; }
-keep class ph.opendtv.DeveloperOptionsActivity { *; }
-keep class ph.opendtv.LicensesActivity { *; }
-keep class ph.opendtv.UsbProbeActivity { *; }
-keep class ph.opendtv.tv.OpenDtvInputService { *; }
-keep class ph.opendtv.tv.SetupActivity { *; }

# Enum valueOf/values by name, used by the prefs and the analyzer's tab constants.
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

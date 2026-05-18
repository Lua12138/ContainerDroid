# Pine registers Java methods from JNI_OnLoad with hard-coded class, method,
# field and descriptor strings. R8 cannot infer these upcalls reliably; if it
# renames members such as Pine.enableFastNative(), RegisterNatives aborts before
# the sandbox can install framework hooks.
-keep class top.canyie.pine.Pine { *; }
-keep class top.canyie.pine.Pine$* { *; }
-keep class top.canyie.pine.PineConfig { *; }
-keep class top.canyie.pine.Ruler { *; }
-keep class top.canyie.pine.Ruler$* { *; }
-keep class top.canyie.pine.entry.** { *; }
-keep class top.canyie.pine.callback.** { *; }
-keep class top.canyie.pine.utils.** { *; }

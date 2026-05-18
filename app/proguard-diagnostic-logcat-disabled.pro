# Used only when blackboxDiagnosticLogcatEnabled=false.
# Keep names and members stable for reflection-heavy sandbox code, but still
# allow R8 to optimize away calls declared as side-effect free below.
-dontobfuscate
-keepattributes Signature,*Annotation*,InnerClasses,EnclosingMethod
-keep,allowoptimization class ** { *; }

-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
    public static int w(...);
    public static int e(...);
    public static int println(...);
    public static boolean isLoggable(...);
}

-assumenosideeffects class top.canyie.pine.Pine {
    public static void log(...);
}

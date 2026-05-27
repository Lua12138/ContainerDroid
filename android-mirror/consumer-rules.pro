-keepattributes *Annotation*

# BlackReflection builds java.lang.reflect.Proxy instances for generated
# black.* interfaces and resolves framework members with Method.getName().
# Obfuscating these interface method names makes the proxy look up names such
# as "a" instead of framework names such as "myUserId".
-keep class black.** { *; }

# BlackReflection itself interprets the generated black.* runtime annotations.
# Keep only its small runtime/annotation surface here so host apps do not need
# broad app-level keep rules for Bcore or android-mirror internals.
-keep class top.niunaijun.blackreflection.BlackReflection { *; }
-keep class top.niunaijun.blackreflection.BlackReflection$* { *; }
-keep class top.niunaijun.blackreflection.BlackNullPointerException { *; }
-keep class top.niunaijun.blackreflection.annotation.** { *; }
-keep class top.niunaijun.blackreflection.utils.** { *; }

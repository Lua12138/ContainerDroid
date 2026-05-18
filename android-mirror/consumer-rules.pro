-keepattributes *Annotation*

# BlackReflection builds java.lang.reflect.Proxy instances for generated
# black.* interfaces and resolves framework members with Method.getName().
# Obfuscating these interface method names makes the proxy look up names such
# as "a" instead of framework names such as "myUserId".
-keep class black.** { *; }

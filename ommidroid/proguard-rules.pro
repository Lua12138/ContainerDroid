# Intentionally empty.
#
# Bcore, android-mirror, and Pine publish the reflection/JNI keep rules they
# actually require through their own consumer ProGuard files. Duplicating broad
# host-app keep rules here would make ommidroid release APKs less obfuscated
# without adding runtime compatibility.

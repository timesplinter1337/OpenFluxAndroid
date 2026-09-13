# JSch resolves cipher/KEX/signature implementations reflectively by class name,
# so R8 must not rename or strip them in release builds.
-keep class com.jcraft.jsch.** { *; }
-dontwarn com.jcraft.jsch.**

# Optional logging/JCE backends referenced by the JSch fork but not bundled.
-dontwarn org.slf4j.**
-dontwarn org.bouncycastle.**

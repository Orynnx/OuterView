# Xposed discovers these entry points and hook implementations outside the
# normal Android component graph. Keep their names and reflective members.
-keep class org.orynnx.outerview.hook.** { *; }

# The manager and injected host share the AI API, validation helpers and AIDL.
-keep class org.orynnx.outerview.core.** { *; }

# Kavaref's JVM reflection signatures mention this JDK-only type; Android's
# runtime does not ship it, and the resolver never loads it on-device.
-dontwarn java.lang.reflect.AnnotatedType

# Xposed discovers these entry points and hook implementations outside the
# normal Android component graph. Keep their names and reflective members.
-keep class org.orynnx.outerview.hook.** { *; }

# These Parcelable/data contracts cross the manager-app/host boundary by name.
-keep class org.orynnx.outerview.core.** { *; }

# Gson serializes the persisted card and wallpaper models reflectively.
-keep class org.orynnx.outerview.core.internal.** { *; }

# Kavaref's JVM reflection signatures mention this JDK-only type; Android's
# runtime does not ship it, and the resolver never loads it on-device.
-dontwarn java.lang.reflect.AnnotatedType

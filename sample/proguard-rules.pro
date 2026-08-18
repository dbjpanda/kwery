# Kwery's API is kept because the instrumentation test calls it directly, and
# the test APK is not part of R8's input: R8 sees the sample app never calling
# encodeKey and removes it, which is correct behaviour for that app and useless
# for this test. A real consumer that persists queries reaches the codec through
# its own code and needs no rule.
#
# This keeps the CLASSES. It deliberately does not use -keepnames or
# -keepclassmembernames, so R8 is still free to rename enum constants. That is
# the thing under test: Kwery encodes enum key parts by `name`, and if R8
# rewrote them every persisted key would change, and the cache would miss on
# every cold start of a released app while working in debug.
-keep class dev.kwery.** { *; }

# Test infrastructure. An instrumentation APK excludes anything already present
# in the app APK, so a class the runner reaches reflectively must survive R8
# HERE or it exists nowhere. Without this the test process dies in onCreate and
# the report shows zero tests rather than a failure.
-keep class androidx.tracing.** { *; }
# The test code is Kotlin and unminified, so it calls stdlib members the app
# itself never touches. Those live in the app APK and R8 strips them.
-keep class kotlin.** { *; }
-keep class kotlinx.coroutines.** { *; }
-dontwarn com.google.errorprone.annotations.**
-dontwarn javax.annotation.**

# Applies to the instrumentation APK only. The app under test keeps its own
# minification, which is the entire point of running these tests against the
# release build: Kwery's code goes through R8, and the test asserting that it
# still works does not need to.
#
# Without this the test process dies in AndroidJUnitRunner.onCreate with
# NoClassDefFoundError, because the runner reaches its dependencies reflectively
# and R8 cannot see they are used. The report then shows zero tests rather than
# a failure, which is the worst possible way for a test suite to break.
-dontshrink
-dontoptimize
-dontobfuscate

-dontwarn com.google.errorprone.annotations.**
-dontwarn javax.annotation.**
-dontwarn org.checkerframework.**

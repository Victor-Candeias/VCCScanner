# R8 rules for the instrumented test APK, used when the suite runs against the minified build with
# -PvccTestBuildType=release. They never reach the published application.
#
# The test APK is shrunk with the application on its classpath and with the application's mapping
# applied, so these rules must not cover application classes: keeping a class the application
# renamed would leave the test calling a name that no longer exists.
-keep class pt.vcc.scanner.*Test { *; }
-keepclassmembers class pt.vcc.scanner.*Test { @org.junit.Test <methods>; }
-keep class androidx.test.** { *; }
-keep class org.junit.** { *; }
-keep class junit.** { *; }
-keep class org.hamcrest.** { *; }

# androidx.test is compiled against Error Prone annotations that are compile time only.
-dontwarn com.google.errorprone.annotations.**

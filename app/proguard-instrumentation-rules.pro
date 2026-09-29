# Extra keep rules applied to the release build only while the instrumented suite runs against it
# (-PvccTestBuildType=release). They are not part of the published application.
#
# The test APK is shrunk with the application on its classpath, so anything the instrumentation
# harness reaches through the application classloader has to survive the application's own R8 run.
-keep class androidx.tracing.Trace { *; }
-keep class kotlin.** { *; }
-dontwarn kotlin.**

# The suite drives DocumentEngine, Metadata and Room directly. R8 is free to inline and drop those
# entry points in the shipped application, which only ever calls them from MainActivity, so they
# have to be kept while the tests are the caller.
-keep class pt.vcc.scanner.** { *; }
-keep class androidx.room.** { *; }

# R8 rules for the minified release build. The app itself has no reflection of its own: every
# entry point is declared in AndroidManifest.xml. What follows protects the code that third party
# libraries and generated code reach by name.

# Room looks up <Database>_Impl with Class.forName and calls its no-argument constructor.
-keep class * extends androidx.room.RoomDatabase { <init>(); }
-keep class pt.vcc.scanner.ScannerDatabase_Impl { <init>(); }
# The generated DAO and the FTS4 index read the entity fields directly.
-keepclassmembers @androidx.room.Entity class * { <fields>; <init>(...); }
-keepclassmembers class androidx.room.RoomDatabase$Callback { *; }
-dontwarn androidx.room.paging.**

# ML Kit text recognition, barcode scanning and language identification load their detectors and
# native pipelines through the Google Play Services component registry, which resolves by name.
-keep class com.google.mlkit.** { *; }
-keep interface com.google.mlkit.** { *; }
-keep class com.google.android.gms.internal.mlkit_** { *; }
-keep class com.google.android.gms.vision.** { *; }
-dontwarn com.google.mlkit.**

# The document scanner result is handed back through an IntentSender payload that is deserialized
# by the Google Play Services client, so its parcelable creators must survive shrinking.
-keepclassmembers class * implements android.os.Parcelable { public static final ** CREATOR; }

# Generic signatures and annotations are required by Room, ML Kit and the Play Services registry.
-keepattributes Signature, InnerClasses, EnclosingMethod, *Annotation*, RuntimeVisible*Annotation*

# Keep readable stack traces for crash reports without exposing the original file names.
-keepattributes SourceFile, LineNumberTable
-renamesourcefileattribute SourceFile

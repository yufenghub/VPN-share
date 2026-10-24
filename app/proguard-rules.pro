-keep class dev.shizzi.TetherService { *; }

-keep interface dev.shizzi.ITetherService { *; }
-keep class dev.shizzi.ITetherService$* { *; }

-keep class dev.shizzi.adb.ShellBootstrap { public static void main(java.lang.String[]); }
-keep class dev.shizzi.adb.ShellServerMain { public static void main(java.lang.String[]); }

-keep class org.lsposed.hiddenapibypass.** { *; }
-dontwarn org.lsposed.hiddenapibypass.**

-keep class dev.shizzi.App
-keep class dev.shizzi.MainActivity
-keep class dev.shizzi.SessionService

-keepattributes RuntimeVisibleAnnotations,RuntimeVisibleParameterAnnotations
-keepattributes Signature,InnerClasses,EnclosingMethod

-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

-keep class io.github.muntashirakon.crypto.spake2.Spake2Context { *; }

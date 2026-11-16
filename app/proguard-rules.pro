# LockIn release rules. Shrink and optimise, but keep names so crash reports stay readable.
-dontobfuscate

# Firebase, CameraX and ML Kit bring their own consumer rules. Nothing of ours uses reflection.
-keepattributes SourceFile,LineNumberTable,*Annotation*,Signature,InnerClasses,EnclosingMethod

# Entry points declared in the manifest are kept automatically; these are kept explicitly as well.
-keep class com.godbless.lockin.LockInApplication { *; }
-keep class com.godbless.lockin.MainActivity { *; }
-keep class com.godbless.lockin.lock.LockService { *; }
-keep class com.godbless.lockin.usage.GuardService { *; }
-keep class com.godbless.lockin.push.LockInMessagingService { *; }
-keep class com.godbless.lockin.push.NotifyActionReceiver { *; }

-dontwarn org.slf4j.**
-dontwarn javax.annotation.**

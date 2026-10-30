# JNI: the native bridge constructs these by name and calls these methods.
-keep class io.github.l0gic0l.pepperjack.sim.NativeVector { <init>(...); }
-keepclasseswithmembernames class io.github.l0gic0l.pepperjack.sim.NgSpiceNative {
    native <methods>;
}

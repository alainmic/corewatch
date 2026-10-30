# Keep Activity and TileService entry points
-keep public class com.droid.corewatch.MainActivity
-keep public class com.droid.corewatch.service.CaffeineTileService

# Keep JNI native bridge methods for libcorewatch_accel.so
-keep class com.droid.corewatch.data.AccelIoctlBridge {
    private static native <methods>;
    private native <methods>;
}

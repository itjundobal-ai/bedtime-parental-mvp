package com.master.bedtime.child.recovery;

import android.app.admin.DevicePolicyManager;
import android.content.Context;

import java.lang.reflect.Method;

/**
 * One-time recovery helper for a debuggable legacy build that is still Device Owner.
 * It is not installed as an APK. It is executed with `run-as` so the binder call
 * originates from the legacy app UID, then asks Android to clear that app's
 * Device Owner role. This is only for migrating from the old debug signing key.
 */
public final class OwnerRecovery {
    private static final String PACKAGE_NAME = "com.master.bedtime.child";

    public static void main(String[] args) {
        try {
            Context context = createSystemContext();
            if (context == null) {
                System.err.println("RECOVERY_FAILED: could not obtain Android context");
                System.exit(2);
                return;
            }

            DevicePolicyManager dpm =
                (DevicePolicyManager) context.getSystemService(Context.DEVICE_POLICY_SERVICE);
            if (dpm == null) {
                System.err.println("RECOVERY_FAILED: DevicePolicyManager unavailable");
                System.exit(3);
                return;
            }

            if (!dpm.isDeviceOwnerApp(PACKAGE_NAME)) {
                System.out.println("RECOVERY_OK: package is not Device Owner");
                return;
            }

            // Deprecated by Android for normal product flows, but intentionally used
            // here as a one-time migration escape hatch from the legacy debug build.
            dpm.clearDeviceOwnerApp(PACKAGE_NAME);

            if (dpm.isDeviceOwnerApp(PACKAGE_NAME)) {
                System.err.println("RECOVERY_FAILED: Device Owner still active");
                System.exit(4);
            } else {
                System.out.println("RECOVERY_OK: Device Owner cleared");
            }
        } catch (Throwable t) {
            System.err.println("RECOVERY_FAILED: " + t.getClass().getName() + ": " + t.getMessage());
            t.printStackTrace(System.err);
            System.exit(1);
        }
    }

    private static Context createSystemContext() throws Exception {
        Class<?> activityThreadClass = Class.forName("android.app.ActivityThread");
        Method systemMain = activityThreadClass.getDeclaredMethod("systemMain");
        systemMain.setAccessible(true);
        Object activityThread = systemMain.invoke(null);

        Method getSystemContext = activityThreadClass.getDeclaredMethod("getSystemContext");
        getSystemContext.setAccessible(true);
        return (Context) getSystemContext.invoke(activityThread);
    }

    private OwnerRecovery() {}
}

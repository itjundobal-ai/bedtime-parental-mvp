package com.master.bedtime.child;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;

/** Keeps boot-critical monitor configuration in device-protected storage. */
public final class BedtimeStorage {
    private static final String PREFS = "cfg";
    private BedtimeStorage() {}
    public static SharedPreferences prefs(Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) return context.createDeviceProtectedStorageContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
    public static void mirror(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return;
        SharedPreferences normal=context.getSharedPreferences(PREFS,Context.MODE_PRIVATE); SharedPreferences device=prefs(context); SharedPreferences.Editor e=device.edit();
        e.putBoolean("setup_complete",normal.getBoolean("setup_complete",false)); e.putBoolean("last_active",normal.getBoolean("last_active",false)); e.putBoolean("accounts_confirmed",normal.getBoolean("accounts_confirmed",false)); e.putString("backend",normal.getString("backend","")); e.putString("child",normal.getString("child","child-001")); e.putString("child_token",normal.getString("child_token","")).apply();
    }
    public static void setSetup(Context context,String backend,String child,boolean complete){SharedPreferences.Editor normal=context.getSharedPreferences(PREFS,Context.MODE_PRIVATE).edit();normal.putString("backend",backend).putString("child",child).putBoolean("setup_complete",complete).apply();SharedPreferences.Editor device=prefs(context).edit();device.putString("backend",backend).putString("child",child).putBoolean("setup_complete",complete).apply();}
    public static void setLastActive(Context context,boolean active){context.getSharedPreferences(PREFS,Context.MODE_PRIVATE).edit().putBoolean("last_active",active).apply();prefs(context).edit().putBoolean("last_active",active).apply();}
}

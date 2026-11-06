package de.netzelch.hallowelt;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;

/** Liest die installierte Versionsnummer der App. */
final class BuildConfigInfo {

    private BuildConfigInfo() {}

    static int versionCode(Context context) {
        PackageInfo info = info(context);
        return info == null ? 0 : (int) info.getLongVersionCode();
    }

    static String versionName(Context context) {
        PackageInfo info = info(context);
        return info == null ? "?" : info.versionName;
    }

    private static PackageInfo info(Context context) {
        try {
            return context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
        } catch (PackageManager.NameNotFoundException e) {
            return null;
        }
    }
}

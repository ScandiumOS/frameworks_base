/*
 * Copyright (C) 2014 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.internal.util.nad;

import static android.view.DisplayCutout.BOUNDS_POSITION_LEFT;
import static android.view.DisplayCutout.BOUNDS_POSITION_RIGHT;

import android.Manifest;
import android.content.Context;
import android.content.om.IOverlayManager;
import android.content.om.OverlayInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.res.Resources;
import android.graphics.Point;
import android.graphics.Rect;
import android.hardware.fingerprint.FingerprintManager;
import android.os.PowerManager;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.DisplayCutout;
import android.view.DisplayInfo;
import android.view.IWindowManager;
import android.view.WindowManagerGlobal;
import android.util.DisplayMetrics;
import android.util.Log;

import com.android.internal.statusbar.IStatusBarService;

import com.android.internal.R;

import java.util.List;
import java.util.ArrayList;

public class NadUtils {

    private static final String TAG = "NadUtils";

    private static final boolean DEBUG = false;

    private static final int NO_CUTOUT = -1;

    private static OverlayManager sOverlayService;

    // Check to see if device is WiFi only
/*     public static boolean isWifiOnly(Context context) {
        ConnectivityManager cm = (ConnectivityManager)context.getSystemService(
                Context.CONNECTIVITY_SERVICE);
        return (cm.isNetworkSupported(ConnectivityManager.TYPE_MOBILE) == false);
    } */

    // Check to see if device supports the Fingerprint scanner
    public static boolean hasFingerprintSupport(Context context) {
        FingerprintManager fingerprintManager = (FingerprintManager) context.getSystemService(Context.FINGERPRINT_SERVICE);
        return context.getApplicationContext().checkSelfPermission(Manifest.permission.USE_FINGERPRINT) == PackageManager.PERMISSION_GRANTED &&
                (fingerprintManager != null && fingerprintManager.isHardwareDetected());
    }

    // Check to see if device not only supports the Fingerprint scanner but also if is enrolled
    public static boolean hasFingerprintEnrolled(Context context) {
        FingerprintManager fingerprintManager = (FingerprintManager) context.getSystemService(Context.FINGERPRINT_SERVICE);
        return context.getApplicationContext().checkSelfPermission(Manifest.permission.USE_FINGERPRINT) == PackageManager.PERMISSION_GRANTED &&
                (fingerprintManager != null && fingerprintManager.isHardwareDetected() && fingerprintManager.hasEnrolledFingerprints());
    }

    // Check to see if device has a camera
    public static boolean hasCamera(Context context) {
        return context.getPackageManager().hasSystemFeature(PackageManager.FEATURE_CAMERA);
    }

    // Check to see if device supports NFC
    public static boolean hasNFC(Context context) {
        return context.getPackageManager().hasSystemFeature(PackageManager.FEATURE_NFC);
    }

    // Check to see if device supports Wifi
    public static boolean hasWiFi(Context context) {
        return context.getPackageManager().hasSystemFeature(PackageManager.FEATURE_WIFI);
    }

    // Check to see if device supports Bluetooth
    public static boolean hasBluetooth(Context context) {
        return context.getPackageManager().hasSystemFeature(PackageManager.FEATURE_BLUETOOTH);
    }

    // Check to see if device supports an alterative ambient display package
    public static boolean hasAltAmbientDisplay(Context context) {
        return context.getResources().getBoolean(com.android.internal.R.bool.config_alt_ambient_display);
    }

    // Check to see if device supports A/B (seamless) system updates
    public static boolean isABdevice(Context context) {
        return SystemProperties.getBoolean("ro.build.ab_update", false);
    }

    public static boolean deviceHasFlashlight(Context ctx) {
        return ctx.getPackageManager().hasSystemFeature(PackageManager.FEATURE_CAMERA_FLASH);
    }

    // Check to see if a package is installed
    public static boolean isPackageInstalled(Context context, String pkg, boolean ignoreState) {
        if (pkg != null) {
            try {
                PackageInfo pi = context.getPackageManager().getPackageInfo(pkg, 0);
                if (!pi.applicationInfo.enabled && !ignoreState) {
                    return false;
                }
            } catch (NameNotFoundException e) {
                return false;
            }
        }

        return true;
    }

    public static boolean isPackageInstalled(Context context, String pkg) {
        return isPackageInstalled(context, pkg, true);
    }

    // Method to detect navigation bar is in use
    public static boolean hasNavigationBar(Context context) {
        boolean hasNavbar = false;
        IWindowManager wm = WindowManagerGlobal.getWindowManagerService();
        try {
            hasNavbar = wm.hasNavigationBar(context.getDisplayId());
        } catch (RemoteException ex) {
        }
        return hasNavbar;
    }

    public static boolean deviceSupportNavigationBar(Context context) {
        return deviceSupportNavigationBarForUser(context, UserHandle.USER_CURRENT);
    }

    public static boolean deviceSupportNavigationBarForUser(Context context, int userId) {
        final boolean showByDefault = context.getResources().getBoolean(
                com.android.internal.R.bool.config_showNavigationBar);
        final int hasNavigationBar = Settings.System.getIntForUser(
                context.getContentResolver(),
                Settings.System.FORCE_SHOW_NAVBAR, -1, userId);

        if (hasNavigationBar == -1) {
            String navBarOverride = SystemProperties.get("qemu.hw.mainkeys");
            if ("1".equals(navBarOverride)) {
                return false;
            } else if ("0".equals(navBarOverride)) {
                return true;
            } else {
                return showByDefault;
            }
        } else {
            return hasNavigationBar == 1;
        }
    }

    // Method to detect whether an overlay is enabled or not
    public static boolean isThemeEnabled(String packageName) {
        if (sOverlayService == null) {
            sOverlayService = new OverlayManager();
        }
        try {
            ArrayList<OverlayInfo> infos = new ArrayList<OverlayInfo>();
            infos.addAll(sOverlayService.getOverlayInfosForTarget("android",
                    UserHandle.myUserId()));
            infos.addAll(sOverlayService.getOverlayInfosForTarget("com.android.systemui",
                    UserHandle.myUserId()));
            for (int i = 0, size = infos.size(); i < size; i++) {
                if (infos.get(i).packageName.equals(packageName)) {
                    return infos.get(i).isEnabled();
                }
            }
        } catch (RemoteException e) {
            e.printStackTrace();
        }
        return false;
    }

    public static class OverlayManager {
        private final IOverlayManager mService;

        public OverlayManager() {
            mService = IOverlayManager.Stub.asInterface(
                    ServiceManager.getService(Context.OVERLAY_SERVICE));
        }

        public void setEnabled(String pkg, boolean enabled, int userId)
                throws RemoteException {
            mService.setEnabled(pkg, enabled, userId);
        }

        public List<OverlayInfo> getOverlayInfosForTarget(String target, int userId)
                throws RemoteException {
            return mService.getOverlayInfosForTarget(target, userId);
        }
    }

    // Check if gesture navbar is enabled
    public static boolean isGestureNavbar() {
        return NadUtils.isThemeEnabled("com.android.internal.systemui.navbar.gestural_narrow_back")
                || NadUtils.isThemeEnabled("com.android.internal.systemui.navbar.gestural")
                || NadUtils.isThemeEnabled("com.android.internal.systemui.navbar.gestural_wide_back")
                || NadUtils.isThemeEnabled("com.android.internal.systemui.navbar.gestural_extra_wide_back");
    }

    // Check if device has a notch
    public static boolean hasNotch(Context context) {
        String displayCutout = context.getResources().getString(R.string.config_mainBuiltInDisplayCutout);
        boolean maskDisplayCutout = context.getResources().getBoolean(R.bool.config_maskMainBuiltInDisplayCutout);
        boolean displayCutoutExists = (!TextUtils.isEmpty(displayCutout) && !maskDisplayCutout);
        return displayCutoutExists;
    }

    public static int getCutoutType(Context context) {
        final DisplayInfo info = new DisplayInfo();
        context.getDisplay().getDisplayInfo(info);
        final DisplayCutout cutout = info.displayCutout;
        if (cutout == null) {
            if (DEBUG) Log.v(TAG, "noCutout");
            return NO_CUTOUT;
        }
        final Point displaySize = new Point();
        context.getDisplay().getRealSize(displaySize);
        List<Rect> cutOutBounds = cutout.getBoundingRects();
        if (cutOutBounds != null) {
            for (Rect cutOutRect : cutOutBounds) {
                if (DEBUG) Log.v(TAG, "cutout left= " + cutOutRect.left);
                if (DEBUG) Log.v(TAG, "cutout right= " + cutOutRect.right);
                if (cutOutRect.left == 0 && cutOutRect.right > 0) {  //cutout is located on top left
                    if (DEBUG) Log.v(TAG, "cutout position= " + BOUNDS_POSITION_LEFT);
                    return BOUNDS_POSITION_LEFT;
                } else if (cutOutRect.right == displaySize.x && (displaySize.x - cutOutRect.left) > 0) {  //cutout is located on top right
                    if (DEBUG) Log.v(TAG, "cutout position= " + BOUNDS_POSITION_RIGHT);
                    return BOUNDS_POSITION_RIGHT;
                }
            }
        }
        return NO_CUTOUT;
    }

    public static class QSLayoutUtils {

        public static boolean getQSTileLabelHide(Context context) {
            return Settings.System.getIntForUser(context.getContentResolver(),
                    Settings.System.QS_TILE_LABEL_HIDE,
                    0, UserHandle.USER_CURRENT) == 1;
        }

        public static boolean getQSTileVerticalLayout(Context context) {
            return Settings.System.getIntForUser(context.getContentResolver(),
                    Settings.System.QS_TILE_VERTICAL_LAYOUT,
                    0, UserHandle.USER_CURRENT) == 1;
        }

        public static boolean updateLayout(Context context) {
            final IOverlayManager overlayManager = IOverlayManager.Stub.asInterface(ServiceManager.getService(
                    Context.OVERLAY_SERVICE));
            final int layout_qs = Settings.System.getIntForUser(context.getContentResolver(),
                    Settings.System.QS_LAYOUT,
                    42, UserHandle.USER_CURRENT);
            final int layout_qqs = Settings.System.getIntForUser(context.getContentResolver(),
                    Settings.System.QQS_LAYOUT,
                    22, UserHandle.USER_CURRENT);
            final int row_qs = layout_qs / 10;
            final int col_qs = layout_qs % 10;
            final int row_qqs = layout_qqs / 10;
            for (int i = 0; i < 2; ++i) {
                String pkgName;
                if (i == 0) {
                    pkgName = String.format("com.xtended.qs.portrait.layout_%sx%s", Integer.toString(row_qs), Integer.toString(col_qs));
                } else {
                    pkgName = String.format("com.xtended.qqs.portrait.layout_%sx%s", Integer.toString(row_qqs), Integer.toString(col_qs));
                }
                try {
                    overlayManager.setEnabledExclusiveInCategory(pkgName, UserHandle.USER_CURRENT);
                } catch (RemoteException re) {
                    return false;
                }
            }
            return true;
        }
    }
}

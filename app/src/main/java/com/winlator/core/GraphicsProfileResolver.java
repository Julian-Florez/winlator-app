package com.winlator.core;

import android.content.Context;
import android.os.Build;
import android.util.Log;

import com.winlator.container.GraphicsDrivers;

/** Selects a conservative runtime profile from the device's actual renderer. */
public final class GraphicsProfileResolver {
    private static final String TAG = "Win2APKGraphicsProfile";

    public static final class Profile {
        public final String graphicsDriver;
        public final String graphicsDriverConfig;
        public final String dxwrapper;
        public final String dxwrapperConfig;
        public final String screenSize;
        public final String displayName;

        private Profile(String graphicsDriver, String graphicsDriverConfig,
                        String dxwrapper, String dxwrapperConfig, String screenSize, String displayName) {
            this.graphicsDriver = graphicsDriver;
            this.graphicsDriverConfig = graphicsDriverConfig;
            this.dxwrapper = dxwrapper;
            this.dxwrapperConfig = dxwrapperConfig;
            this.screenSize = screenSize;
            this.displayName = displayName;
        }
    }

    private GraphicsProfileResolver() {}

    public static Profile resolve(Context context) {
        String renderer = safeRenderer(context);
        String vendor = safeVendor(context);
        boolean adreno = renderer.toLowerCase().contains("adreno")
                || vendor.toLowerCase().contains("qualcomm")
                || Build.HARDWARE.toLowerCase().contains("qcom");

        Profile profile;
        if (adreno) {
            profile = new Profile(
                    GraphicsDrivers.TURNIP + "," + GraphicsDrivers.GLADIO,
                    "",
                    "dxvk",
                    "",
                    "1280x720",
                    "Adreno / Turnip + Gladio / DXVK");
        }
        else {
            // This matches the last Pixel profile that produced visible,
            // playable output before the Fcharan and DXVK 2.4.1 experiments.
            profile = new Profile(
                    GraphicsDrivers.VORTEK + "," + GraphicsDrivers.GLADIO,
                    "maxDeviceMemory=1024,imageCacheSize=0",
                    "dxvk",
                    "version=" + DefaultVersion.MALI_DXVK_STABLE + ",maxDeviceMemory=1024",
                    "1280x720",
                    "Mali/unknown / Vortek + Gladio / DXVK-Sarek 1.13.0 + Leegao BCn ETC2");
        }

        Log.i(TAG, "device=" + Build.MANUFACTURER + "/" + Build.MODEL
                + " hardware=" + Build.HARDWARE
                + " renderer=" + renderer + " vendor=" + vendor
                + " profile=" + profile.displayName
                + " graphicsDriver=" + profile.graphicsDriver
                + " dxwrapper=" + profile.dxwrapper);
        return profile;
    }

    private static String safeRenderer(Context context) {
        try {
            String renderer = GPUHelper.glGetRenderer(context);
            return renderer == null ? "" : renderer;
        }
        catch (Throwable e) {
            Log.w(TAG, "renderer detection failed; using conservative profile", e);
            return "";
        }
    }

    private static String safeVendor(Context context) {
        try {
            String vendor = GPUHelper.glGetVendor(context);
            return vendor == null ? "" : vendor;
        }
        catch (Throwable e) {
            return "";
        }
    }
}

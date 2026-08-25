package com.winlator.core;

import android.content.Context;
import android.util.Log;

import com.google.android.play.core.assetpacks.AssetPackLocation;
import com.google.android.play.core.assetpacks.AssetPackManager;
import com.google.android.play.core.assetpacks.AssetPackManagerFactory;

import java.io.File;

/**
 * Records the storage backend exposed by Play Asset Delivery without copying
 * or modifying any asset pack. This is intentionally diagnostic until the
 * direct filesystem provider has passed device tests.
 */
public final class AssetPackDiagnostics {
    private static final String TAG = "Win2APKAssetPack";

    private AssetPackDiagnostics() {}

    public static void log(Context context, CoreConfig config) {
        String[] packNames = config.getApplicationAssetPackNames();
        if (packNames.length == 0) {
            Log.i(TAG, "No application asset packs configured");
            return;
        }

        AssetPackManager manager = AssetPackManagerFactory.getInstance(context);
        for (String packName : packNames) {
            if (packName == null || packName.trim().isEmpty()) continue;
            logPack(manager, packName.trim());
        }
    }

    private static void logPack(AssetPackManager manager, String packName) {
        try {
            AssetPackLocation location = manager.getPackLocation(packName);
            if (location == null) {
                Log.w(TAG, "pack=" + packName + " location=null");
                return;
            }

            String path = location.path();
            String assetsPath = location.assetsPath();
            String pathDescription = path == null ? "null" : path;
            String assetsPathDescription = assetsPath == null ? "null" : assetsPath;
            Log.i(TAG, "pack=" + packName
                    + " storageMethod=" + location.packStorageMethod()
                    + " path=" + pathDescription
                    + " assetsPath=" + assetsPathDescription
                    + " assetsDirectory=" + describeDirectory(assetsPath));
        }
        catch (Exception e) {
            Log.e(TAG, "pack=" + packName + " diagnostic failed", e);
        }
    }

    private static String describeDirectory(String path) {
        if (path == null || path.isEmpty()) return "N/A";
        File directory = new File(path);
        return "exists=" + directory.isDirectory()
                + ", readable=" + directory.canRead()
                + ", files=" + (directory.list() == null ? "N/R" : directory.list().length);
    }
}

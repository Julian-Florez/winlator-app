package com.winlator.core;

import android.content.Context;
import android.system.Os;
import android.util.Log;

import com.google.android.play.core.assetpacks.AssetPackLocation;
import com.google.android.play.core.assetpacks.AssetPackManager;
import com.google.android.play.core.assetpacks.AssetPackManagerFactory;
import com.google.android.play.core.assetpacks.model.AssetPackStorageMethod;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Commits the application payload directly into the configured Wine prefix.
 *
 * The operation is deliberately a move, not a copy.  The marker is written
 * only after all files have been moved and validated, so a partially completed
 * installation is retried on the next launch instead of being treated as
 * complete.  This class only accepts Play Asset Delivery's STORAGE_FILES
 * backend; an APK-backed pack would make the no-duplicate-data guarantee
 * impossible.
 */
public final class DirectFilesAssetManager {
    private static final String TAG = "Win2APKDirectFiles";
    private static final String MARKER = ".win2apk-moved-files-v1";

    private DirectFilesAssetManager() {}

    public static boolean hasValidInstallation(CoreConfig config, File destination) {
        return isValidInstallation(config, destination);
    }

    /** Adopts a complete legacy extraction without copying it again. */
    public static boolean adoptExistingInstallation(CoreConfig config, File destination) {
        boolean exactPayload = isValidPayload(config, destination);
        boolean usableLegacyPayload = isUsableLegacyPayload(destination);
        if (!exactPayload && !usableLegacyPayload) return false;
        File parent = destination.getParentFile();
        if (parent == null) return false;
        File marker = new File(parent, MARKER);
        File temporary = new File(parent, MARKER + ".tmp");
        String markerContent = "files=" + countFiles(destination)
                + "\nbytes=" + countBytes(destination) + "\n"
                + (exactPayload ? "" : "legacy=true\n");
        if (!FileUtils.writeString(temporary, markerContent)) return false;
        if (!temporary.renameTo(marker)) return false;
        Log.i(TAG, (exactPayload ? "legacy extraction adopted" : "usable legacy extraction adopted")
                + " without copy marker=" + marker.getAbsolutePath()
                + " files=" + countFiles(destination) + " bytes=" + countBytes(destination));
        return true;
    }

    public static boolean moveIntoPlace(Context context, CoreConfig config, File destination) {
        if (isValidInstallation(config, destination)) {
            Log.i(TAG, "move marker fast path destination=" + destination.getAbsolutePath()
                    + " files=" + countFiles(destination) + " bytes=" + countBytes(destination));
            return true;
        }

        String[] packNames = config.getApplicationAssetPackNames();
        if (packNames.length == 0) {
            Log.e(TAG, "direct-files enabled but no asset packs are configured");
            return false;
        }

        AssetPackManager manager = AssetPackManagerFactory.getInstance(context);
        Map<String, File> sources = new HashMap<>();
        try {
            for (String packName : packNames) {
                AssetPackLocation location = manager.getPackLocation(packName);
                if (location == null || location.assetsPath() == null) {
                    Log.e(TAG, "pack=" + packName + " has no assetsPath");
                    return false;
                }
                if (location.packStorageMethod() != AssetPackStorageMethod.STORAGE_FILES) {
                    Log.e(TAG, "pack=" + packName + " storageMethod="
                            + location.packStorageMethod() + " is not STORAGE_FILES");
                    return false;
                }
                File root = new File(location.assetsPath());
                if (!root.isDirectory()) {
                    Log.e(TAG, "pack=" + packName + " assets directory missing=" + root);
                    return false;
                }
                collectFiles(root, root, sources);
                Log.i(TAG, "pack=" + packName + " source=" + root.getAbsolutePath());
            }
        }
        catch (RuntimeException e) {
            Log.e(TAG, "unable to enumerate direct-files sources", e);
            return false;
        }

        long expectedFiles = config.getDirectFilesExpectedCount();
        long expectedBytes = config.getDirectFilesExpectedBytes();
        long actualBytes = countBytes(sources.values());
        if ((expectedFiles >= 0 && sources.size() != expectedFiles)
                || (expectedBytes >= 0 && actualBytes != expectedBytes)) {
            Log.e(TAG, "source validation failed files=" + sources.size() + " bytes=" + actualBytes
                    + " expectedFiles=" + expectedFiles + " expectedBytes=" + expectedBytes);
            return false;
        }

        File parent = destination.getParentFile();
        if (parent == null || !parent.isDirectory() && !parent.mkdirs()) {
            Log.e(TAG, "destination parent unavailable=" + parent);
            return false;
        }
        try {
            long destinationDevice = Os.stat(parent.getAbsolutePath()).st_dev;
            for (File source : sources.values()) {
                long sourceDevice = Os.stat(source.getAbsolutePath()).st_dev;
                if (sourceDevice != destinationDevice) {
                    Log.e(TAG, "move preflight failed different filesystems source=" + sourceDevice
                            + " destination=" + destinationDevice);
                    return false;
                }
            }
            Log.i(TAG, "move preflight st_dev source/destination=" + destinationDevice);
        }
        catch (Exception e) {
            Log.e(TAG, "move preflight stat failed", e);
            return false;
        }

        if (destination.exists() && !FileUtils.delete(destination)) {
            Log.e(TAG, "unable to clear incomplete destination=" + destination);
            return false;
        }
        if (!destination.mkdirs()) {
            Log.e(TAG, "unable to create destination=" + destination);
            return false;
        }

        List<File[]> movedFiles = new ArrayList<>();
        try {
            for (Map.Entry<String, File> entry : sources.entrySet()) {
                File target = new File(destination, entry.getKey());
                File targetParent = target.getParentFile();
                if (targetParent != null && !targetParent.isDirectory() && !targetParent.mkdirs()) {
                    throw new IOException("unable to create " + targetParent);
                }
                moveFile(entry.getValue(), target);
                movedFiles.add(new File[]{entry.getValue(), target});
            }

            if (!isValidPayload(config, destination)) {
                throw new IOException("post-move validation failed");
            }

            File marker = new File(parent, MARKER);
            File temporary = new File(parent, MARKER + ".tmp");
            if (!FileUtils.writeString(temporary, "files=" + sources.size() + "\nbytes=" + actualBytes + "\n")) {
                throw new IOException("unable to write transaction marker");
            }
            if (!temporary.renameTo(marker)) {
                throw new IOException("unable to commit transaction marker");
            }
            Log.i(TAG, "move transaction committed marker=" + marker.getAbsolutePath()
                    + " files=" + sources.size() + " bytes=" + actualBytes + " valid=true");
            return true;
        }
        catch (Exception e) {
            Log.e(TAG, "move transaction rolled back", e);
            for (File[] pair : movedFiles) {
                try {
                    moveFile(pair[1], pair[0]);
                }
                catch (IOException rollbackFailure) {
                    Log.e(TAG, "unable to restore source during rollback=" + pair[0], rollbackFailure);
                }
            }
            FileUtils.delete(destination);
            return false;
        }
    }

    private static void moveFile(File source, File target) throws IOException {
        try {
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE);
        }
        catch (IOException atomicFailure) {
            Files.move(source.toPath(), target.toPath());
        }
    }

    private static void collectFiles(File root, File current, Map<String, File> output) {
        File[] children = current.listFiles();
        if (children == null) return;
        for (File child : children) {
            if (Files.isSymbolicLink(child.toPath())) {
                throw new IllegalStateException("symbolic link in asset pack: " + child);
            }
            if (child.isDirectory()) {
                collectFiles(root, child, output);
            }
            else if (child.isFile()) {
                String relative = root.toPath().relativize(child.toPath()).toString();
                if (output.put(relative, child) != null) {
                    throw new IllegalStateException("duplicate payload path: " + relative);
                }
            }
        }
    }

    private static boolean isValidInstallation(CoreConfig config, File destination) {
        File parent = destination.getParentFile();
        if (parent == null) return false;
        File marker = new File(parent, MARKER);
        if (!marker.isFile()) return false;
        String markerContent = FileUtils.readString(marker);
        return markerContent != null && markerContent.contains("legacy=true")
                ? isUsableLegacyPayload(destination) : isValidPayload(config, destination);
    }

    private static boolean isValidPayload(CoreConfig config, File destination) {
        if (!destination.isDirectory()) return false;
        long expectedFiles = config.getDirectFilesExpectedCount();
        long expectedBytes = config.getDirectFilesExpectedBytes();
        long files = countFiles(destination);
        long bytes = countBytes(destination);
        return (expectedFiles < 0 || files == expectedFiles)
                && (expectedBytes < 0 || bytes == expectedBytes)
                && countSymlinks(destination) == 0;
    }

    private static boolean isUsableLegacyPayload(File destination) {
        if (!destination.isDirectory()) return false;
        File executable = new File(destination, "Cuphead.exe");
        File dataDirectory = new File(destination, "Cuphead_Data");
        File globalManagers = new File(dataDirectory, "globalgamemanagers");
        return executable.isFile() && dataDirectory.isDirectory() && globalManagers.isFile()
                && countFiles(destination) > 100;
    }

    private static long countFiles(File directory) {
        long result = 0;
        File[] children = directory.listFiles();
        if (children == null) return 0;
        for (File child : children) {
            if (Files.isSymbolicLink(child.toPath())) continue;
            if (child.isDirectory()) result += countFiles(child);
            else if (child.isFile()) result++;
        }
        return result;
    }

    private static long countBytes(File directory) {
        long result = 0;
        File[] children = directory.listFiles();
        if (children == null) return 0;
        for (File child : children) {
            if (Files.isSymbolicLink(child.toPath())) continue;
            if (child.isDirectory()) result += countBytes(child);
            else if (child.isFile()) result += child.length();
        }
        return result;
    }

    private static long countBytes(Iterable<File> files) {
        long result = 0;
        for (File file : files) result += file.length();
        return result;
    }

    private static long countSymlinks(File directory) {
        long result = 0;
        File[] children = directory.listFiles();
        if (children == null) return 0;
        for (File child : children) {
            if (Files.isSymbolicLink(child.toPath())) result++;
            else if (child.isDirectory()) result += countSymlinks(child);
        }
        return result;
    }
}

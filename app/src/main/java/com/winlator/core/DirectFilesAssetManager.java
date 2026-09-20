package com.winlator.core;

import android.content.Context;
import android.system.Os;
import android.util.Log;

import com.google.android.play.core.assetpacks.AssetPackLocation;
import com.google.android.play.core.assetpacks.AssetPackManager;
import com.google.android.play.core.assetpacks.AssetPackManagerFactory;
import com.google.android.play.core.assetpacks.model.AssetPackStorageMethod;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Moves a generated Play Asset Delivery payload into the Wine prefix. */
public final class DirectFilesAssetManager {
    private static final String TAG = "Win2APKDirectFiles";
    private static final String MARKER = ".win2apk-moved-files-v2";
    private static final int BUFFER_SIZE = 1024 * 1024;

    private DirectFilesAssetManager() {}

    public static boolean hasValidInstallation(CoreConfig config, File destination) {
        File parent = destination.getParentFile();
        return parent != null && new File(parent, MARKER).isFile()
                && isValidPayload(config, destination);
    }

    /** Adopts a complete destination produced by an earlier compatible build. */
    public static boolean adoptExistingInstallation(CoreConfig config, File destination) {
        if (!isValidPayload(config, destination)) return false;
        File parent = destination.getParentFile();
        if (parent == null) return false;
        return commitMarker(config, parent);
    }

    public static boolean moveIntoPlace(Context context, CoreConfig config, File destination) {
        if (hasValidInstallation(config, destination)) return true;
        try {
            PayloadManifest manifest = loadManifest(context, config);
            validateManifest(config, manifest);
            Map<String, File> packRoots = resolvePackRoots(context, config.getApplicationAssetPackNames());
            File parent = destination.getParentFile();
            if (parent == null || (!parent.isDirectory() && !parent.mkdirs())) {
                throw new IOException("destination parent unavailable: " + parent);
            }
            if (!destination.isDirectory() && !destination.mkdirs()) {
                throw new IOException("unable to create destination: " + destination);
            }

            for (String relative : manifest.directories) {
                File directory = safeResolve(destination, relative);
                if (!directory.isDirectory() && !directory.mkdirs()) {
                    throw new IOException("unable to create payload directory: " + directory);
                }
            }
            for (PayloadFile file : manifest.files) {
                installFile(destination, parent, packRoots, file);
            }
            if (!isValidPayload(config, destination)) {
                throw new IOException("post-move aggregate validation failed");
            }
            if (!commitMarker(config, parent)) {
                throw new IOException("unable to commit installation marker");
            }
            Log.i(TAG, "move transaction committed files=" + manifest.files.size()
                    + " bytes=" + config.getDirectFilesExpectedBytes());
            return true;
        }
        catch (Exception error) {
            Log.e(TAG, "move transaction paused; the next launch can resume it", error);
            return false;
        }
    }

    private static PayloadManifest loadManifest(Context context, CoreConfig config)
            throws JSONException, IOException {
        byte[] data = FileUtils.read(context, config.getPayloadManifestAsset());
        if (data == null) throw new IOException("unable to read payload manifest");
        JSONObject root = new JSONObject(new String(data, StandardCharsets.UTF_8));
        JSONArray directoryArray = root.optJSONArray("directories");
        JSONArray fileArray = root.getJSONArray("files");
        List<String> directories = new ArrayList<>();
        List<PayloadFile> files = new ArrayList<>();
        if (directoryArray != null) {
            for (int index = 0; index < directoryArray.length(); index++) {
                directories.add(requireRelativePath(directoryArray.getString(index)));
            }
        }
        for (int index = 0; index < fileArray.length(); index++) {
            JSONObject source = fileArray.getJSONObject(index);
            String path = requireRelativePath(source.getString("path"));
            long size = source.getLong("size");
            String sha256 = source.getString("sha256").toLowerCase(Locale.ROOT);
            if (size < 0 || !sha256.matches("[0-9a-f]{64}")) {
                throw new JSONException("invalid payload entry: " + path);
            }
            JSONArray segmentArray = source.getJSONArray("segments");
            if (segmentArray.length() == 0) throw new JSONException("file has no segments: " + path);
            List<Segment> segments = new ArrayList<>();
            long expectedOffset = 0;
            for (int segmentIndex = 0; segmentIndex < segmentArray.length(); segmentIndex++) {
                JSONObject value = segmentArray.getJSONObject(segmentIndex);
                Segment segment = new Segment(
                        value.getString("pack"),
                        requireRelativePath(value.getString("assetPath")),
                        value.getLong("offset"),
                        value.getLong("size")
                );
                if (segment.offset != expectedOffset || segment.size < 0) {
                    throw new JSONException("invalid segment sequence: " + path);
                }
                expectedOffset += segment.size;
                segments.add(segment);
            }
            if (expectedOffset != size) throw new JSONException("segment size mismatch: " + path);
            files.add(new PayloadFile(path, size, sha256, segments));
        }
        return new PayloadManifest(directories, files);
    }

    private static void validateManifest(CoreConfig config, PayloadManifest manifest)
            throws IOException {
        long bytes = 0;
        for (PayloadFile file : manifest.files) bytes += file.size;
        if (manifest.files.size() != config.getDirectFilesExpectedCount()
                || bytes != config.getDirectFilesExpectedBytes()) {
            throw new IOException("payload manifest totals do not match win2apk.json");
        }
    }

    private static Map<String, File> resolvePackRoots(Context context, String[] packNames)
            throws IOException {
        if (packNames.length == 0) throw new IOException("no asset packs configured");
        AssetPackManager manager = AssetPackManagerFactory.getInstance(context);
        Map<String, File> roots = new HashMap<>();
        for (String packName : packNames) {
            AssetPackLocation location = manager.getPackLocation(packName);
            if (location == null || location.assetsPath() == null) {
                throw new IOException("asset pack has no assetsPath: " + packName);
            }
            if (location.packStorageMethod() != AssetPackStorageMethod.STORAGE_FILES) {
                throw new IOException("asset pack is not STORAGE_FILES: " + packName);
            }
            File root = new File(location.assetsPath());
            if (!root.isDirectory()) throw new IOException("asset pack directory missing: " + root);
            roots.put(packName, root);
            Log.i(TAG, "pack=" + packName + " source=" + root.getAbsolutePath());
        }
        return roots;
    }

    private static void installFile(File destination, File destinationDeviceRoot,
                                    Map<String, File> packRoots, PayloadFile file)
            throws Exception {
        File target = safeResolve(destination, file.path);
        File targetParent = target.getParentFile();
        if (targetParent != null && !targetParent.isDirectory() && !targetParent.mkdirs()) {
            throw new IOException("unable to create " + targetParent);
        }

        if (isExpectedFile(target, file)) {
            deleteRemainingSegments(packRoots, file);
            return;
        }
        if (file.segments.size() == 1) {
            Segment segment = file.segments.get(0);
            File source = sourceFor(packRoots, segment);
            if (!source.isFile()) throw new IOException("payload source missing: " + source);
            ensureSameFilesystem(destinationDeviceRoot, source);
            if (target.exists() && !FileUtils.delete(target)) {
                throw new IOException("unable to replace invalid destination: " + target);
            }
            moveFile(source, target);
            if (!isExpectedFile(target, file)) {
                throw new IOException("hash validation failed after moving " + file.path);
            }
            return;
        }
        assembleChunks(destinationDeviceRoot, packRoots, file, target);
    }

    private static void assembleChunks(File destinationDeviceRoot, Map<String, File> packRoots,
                                       PayloadFile file, File target) throws Exception {
        File partial = new File(target.getParentFile(), target.getName() + ".win2apk-part");
        long completed = partial.isFile() ? partial.length() : 0;
        long boundary = 0;
        int next = 0;
        while (next < file.segments.size() && boundary + file.segments.get(next).size <= completed) {
            boundary += file.segments.get(next).size;
            next++;
        }
        if (completed != boundary) {
            try (RandomAccessFile output = new RandomAccessFile(partial, "rw")) {
                output.setLength(boundary);
            }
            completed = boundary;
        }

        for (int index = next; index < file.segments.size(); index++) {
            Segment segment = file.segments.get(index);
            if (segment.offset != completed) throw new IOException("chunk offset mismatch: " + file.path);
            File source = sourceFor(packRoots, segment);
            if (!source.isFile() || source.length() != segment.size) {
                throw new IOException("chunk source missing or invalid: " + source);
            }
            ensureSameFilesystem(destinationDeviceRoot, source);
            if (completed == 0 && index == 0) {
                moveFile(source, partial);
            }
            else {
                append(source, partial);
                if (!source.delete()) throw new IOException("unable to delete consumed chunk: " + source);
            }
            completed += segment.size;
        }
        if (partial.length() != file.size || !hash(partial).equals(file.sha256)) {
            throw new IOException("assembled file hash mismatch: " + file.path);
        }
        if (target.exists() && !FileUtils.delete(target)) {
            throw new IOException("unable to replace target: " + target);
        }
        moveFile(partial, target);
    }

    private static void append(File source, File destination) throws IOException {
        byte[] buffer = new byte[BUFFER_SIZE];
        try (FileInputStream input = new FileInputStream(source);
             FileOutputStream output = new FileOutputStream(destination, true)) {
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (read > 0) output.write(buffer, 0, read);
            }
            output.getFD().sync();
        }
    }

    private static void deleteRemainingSegments(Map<String, File> packRoots, PayloadFile file)
            throws IOException {
        for (Segment segment : file.segments) {
            File source = sourceFor(packRoots, segment);
            if (source.exists() && !source.delete()) {
                throw new IOException("unable to remove duplicate source: " + source);
            }
        }
    }

    private static File sourceFor(Map<String, File> roots, Segment segment) throws IOException {
        File root = roots.get(segment.pack);
        if (root == null) throw new IOException("unknown asset pack: " + segment.pack);
        File source = safeResolve(root, segment.assetPath);
        if (Files.isSymbolicLink(source.toPath())) {
            throw new IOException("symbolic link in asset pack: " + source);
        }
        return source;
    }

    private static void ensureSameFilesystem(File destinationRoot, File source) throws Exception {
        long destinationDevice = Os.stat(destinationRoot.getAbsolutePath()).st_dev;
        long sourceDevice = Os.stat(source.getAbsolutePath()).st_dev;
        if (destinationDevice != sourceDevice) {
            throw new IOException("source and Wine prefix are on different filesystems");
        }
    }

    private static boolean isExpectedFile(File target, PayloadFile file) {
        if (!target.isFile() || target.length() != file.size) return false;
        try {
            return hash(target).equals(file.sha256);
        }
        catch (Exception error) {
            return false;
        }
    }

    private static String hash(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] buffer = new byte[BUFFER_SIZE];
        try (FileInputStream input = new FileInputStream(file)) {
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (read > 0) digest.update(buffer, 0, read);
            }
        }
        StringBuilder result = new StringBuilder(64);
        for (byte value : digest.digest()) result.append(String.format(Locale.ROOT, "%02x", value));
        return result.toString();
    }

    private static void moveFile(File source, File target) throws IOException {
        try {
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE);
        }
        catch (IOException atomicFailure) {
            Files.move(source.toPath(), target.toPath());
        }
    }

    private static File safeResolve(File root, String relative) throws IOException {
        Path base = root.toPath().toAbsolutePath().normalize();
        Path candidate = base.resolve(relative).normalize();
        if (!candidate.startsWith(base)) throw new IOException("payload path escapes root: " + relative);
        return candidate.toFile();
    }

    private static String requireRelativePath(String path) throws JSONException {
        if (path.isEmpty() || path.startsWith("/") || path.startsWith("\\")
                || path.contains("\\") || path.contains("\u0000")) {
            throw new JSONException("invalid payload path: " + path);
        }
        for (String part : path.split("/")) {
            if (part.isEmpty() || part.equals(".") || part.equals("..")) {
                throw new JSONException("invalid payload path: " + path);
            }
        }
        return path;
    }

    private static boolean commitMarker(CoreConfig config, File parent) {
        File marker = new File(parent, MARKER);
        File temporary = new File(parent, MARKER + ".tmp");
        String contents = "files=" + config.getDirectFilesExpectedCount()
                + "\nbytes=" + config.getDirectFilesExpectedBytes() + "\n";
        return FileUtils.writeString(temporary, contents) && temporary.renameTo(marker);
    }

    private static boolean isValidPayload(CoreConfig config, File destination) {
        if (!destination.isDirectory()) return false;
        return countFiles(destination) == config.getDirectFilesExpectedCount()
                && countBytes(destination) == config.getDirectFilesExpectedBytes()
                && countSymlinks(destination) == 0;
    }

    private static long countFiles(File directory) {
        long result = 0;
        File[] children = directory.listFiles();
        if (children == null) return 0;
        for (File child : children) {
            if (Files.isSymbolicLink(child.toPath())) continue;
            if (child.isDirectory()) result += countFiles(child);
            else if (child.isFile() && !child.getName().endsWith(".win2apk-part")) result++;
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
            else if (child.isFile() && !child.getName().endsWith(".win2apk-part")) result += child.length();
        }
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

    private static final class PayloadManifest {
        final List<String> directories;
        final List<PayloadFile> files;
        PayloadManifest(List<String> directories, List<PayloadFile> files) {
            this.directories = directories;
            this.files = files;
        }
    }

    private static final class PayloadFile {
        final String path;
        final long size;
        final String sha256;
        final List<Segment> segments;
        PayloadFile(String path, long size, String sha256, List<Segment> segments) {
            this.path = path;
            this.size = size;
            this.sha256 = sha256;
            this.segments = segments;
        }
    }

    private static final class Segment {
        final String pack;
        final String assetPath;
        final long offset;
        final long size;
        Segment(String pack, String assetPath, long offset, long size) {
            this.pack = pack;
            this.assetPath = assetPath;
            this.offset = offset;
            this.size = size;
        }
    }
}

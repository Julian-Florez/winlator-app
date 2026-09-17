package com.winlator.container;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.util.Log;

import androidx.preference.PreferenceManager;

import com.google.android.gms.tasks.Tasks;
import com.google.android.play.core.assetpacks.AssetPackLocation;
import com.google.android.play.core.assetpacks.AssetPackManager;
import com.google.android.play.core.assetpacks.AssetPackManagerFactory;
import com.google.android.play.core.assetpacks.AssetPackState;
import com.google.android.play.core.assetpacks.AssetPackStates;
import com.google.android.play.core.assetpacks.model.AssetPackStatus;
import com.winlator.R;
import com.winlator.box64.Box64Preset;
import com.winlator.core.Callback;
import com.winlator.core.CoreConfig;
import com.winlator.core.FileUtils;
import com.winlator.core.TarCompressorUtils;
import com.winlator.core.WineInfo;
import com.winlator.core.WineThemeManager;
import com.winlator.widget.FrameRating;
import com.winlator.xenvironment.RootFS;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.SequenceInputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Vector;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public class ContainerManager {
    private static final String TAG = "Win2APKAssetPack";
    private static final long ASSET_PACK_TIMEOUT_MS = TimeUnit.MINUTES.toMillis(45);
    private static final long ASSET_PACK_POLL_MS = 1000L;
    private static final String INSTALL_MARKER = ".win2apk-asset-install-complete";
    private static final String TEST_APP_ASSET = "test_app.tzst";
    private static final String TEST_APP_PATH = ".wine/drive_c/Win2APKTest";
    private static final String TEST_APP_NAME = "Win2APKTest";
    private final ArrayList<Container> containers = new ArrayList<>();
    private int maxContainerId = 0;
    private final File homeDir;
    private final Context context;

    public ContainerManager(Context context) {
        this.context = context;
        File rootDir = RootFS.find(context).getRootDir();
        homeDir = new File(rootDir, "home");
        loadContainers();
    }

    public Context getContext() {
        return context;
    }

    public ArrayList<Container> getContainers() {
        return containers;
    }

    private void loadContainers() {
        containers.clear();
        maxContainerId = 0;

        try {
            File[] files = homeDir.listFiles();
            if (files != null) {
                for (File file : files) {
                    if (file.isDirectory()) {
                        if (file.getName().startsWith(RootFS.USER+"-")) {
                            Container container = new Container(Integer.parseInt(file.getName().replace(RootFS.USER+"-", "")));
                            container.setRootDir(new File(homeDir, RootFS.USER+"-"+container.id));
                            JSONObject data = new JSONObject(FileUtils.readString(container.getConfigFile()));
                            container.loadData(data);
                            containers.add(container);
                            maxContainerId = Math.max(maxContainerId, container.id);
                        }
                    }
                }
            }
        }
        catch (JSONException e) {}
    }

    public void activateContainer(Container container) {
        container.setRootDir(new File(homeDir, RootFS.USER+"-"+container.id));
        File file = new File(homeDir, RootFS.USER);
        file.delete();
        FileUtils.symlink(RootFS.USER+"-"+container.id, file.getPath());
    }

    public void createContainerAsync(final JSONObject data, Callback<Container> callback) {
        final Handler handler = new Handler();
        Executors.newSingleThreadExecutor().execute(() -> {
            final Container container = createContainer(data);
            handler.post(() -> callback.call(container));
        });
    }

    public void createDefaultContainerAsync(Callback<Container> callback) {
        if (!containers.isEmpty()) {
            if (callback != null) callback.call(containers.get(0));
            return;
        }

        try {
            SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(context);
            JSONObject data = new JSONObject();
            data.put("name", context.getString(R.string.container)+"-"+getNextContainerId());
            data.put("screenSize", Container.DEFAULT_SCREEN_SIZE);
            data.put("envVars", Container.DEFAULT_ENV_VARS);
            data.put("cpuList", Container.getFallbackCPUList());
            data.put("cpuListWoW64", Container.getFallbackCPUList());
            data.put("graphicsDriver", GraphicsDrivers.getDefaultDriver(context));
            data.put("dxwrapper", Container.DEFAULT_DXWRAPPER);
            data.put("dxwrapperConfig", "");
            data.put("graphicsDriverConfig", "");
            data.put("audioDriver", Container.DEFAULT_AUDIO_DRIVER);
            data.put("audioDriverConfig", "");
            data.put("wincomponents", Container.DEFAULT_WINCOMPONENTS);
            data.put("drives", Container.DEFAULT_DRIVES);
            data.put("hudMode", FrameRating.Mode.DISABLED.ordinal());
            data.put("startupSelection", Container.STARTUP_SELECTION_ESSENTIAL);
            data.put("box64Preset", preferences.getString("box64_preset", Box64Preset.DEFAULT));
            data.put("desktopTheme", WineThemeManager.DEFAULT_DESKTOP_THEME);
            final Handler handler = new Handler();
            Executors.newSingleThreadExecutor().execute(() -> {
                Container container = createContainer(data);
                if (container != null && (!installTestApp(container) || !createTestAppShortcut(container))) {
                    removeContainer(container);
                    container = null;
                }

                final Container result = container;
                handler.post(() -> {
                    if (callback != null) callback.call(result);
                });
            });
        }
        catch (JSONException e) {
            if (callback != null) callback.call(null);
        }
    }

    public void createConfiguredContainerAsync(final CoreConfig config, Callback<Container> callback) {
        if (config == null) {
            if (callback != null) callback.call(null);
            return;
        }

        final Handler handler = new Handler();
        Executors.newSingleThreadExecutor().execute(() -> {
            boolean hadContainers = !containers.isEmpty();
            Container container = hadContainers ? containers.get(0) : createConfiguredContainer(config);
            boolean isNewContainer = !hadContainers;
            if (container != null && !prepareConfiguredContainer(config, container)) {
                if (isNewContainer) removeContainer(container);
                container = null;
            }

            final Container result = container;
            handler.post(() -> {
                if (callback != null) callback.call(result);
            });
        });
    }

    private Container createConfiguredContainer(CoreConfig config) {
        try {
            return createContainer(config.createContainerData(context));
        }
        catch (JSONException e) {
            return null;
        }
    }

    private boolean prepareConfiguredContainer(CoreConfig config, Container container) {
        File applicationDirectory = config.getApplicationDirectory(container);
        if (!applicationDirectory.isDirectory() && !applicationDirectory.mkdirs()) return false;

        File marker = new File(applicationDirectory.getParentFile(), INSTALL_MARKER);
        if (marker.isFile() && applicationDirectory.isDirectory()) {
            Log.i(TAG, "application installation already complete; skipping asset extraction");
            removeConfiguredAssetPacks(config.getApplicationAssetPackNames());
            cleanupLocalTestingSource();

            File shortcutFile = config.getShortcutFile(container);
            File shortcutDirectory = shortcutFile.getParentFile();
            if (shortcutDirectory != null && !shortcutDirectory.isDirectory() && !shortcutDirectory.mkdirs()) return false;
            return FileUtils.writeString(shortcutFile, config.getShortcutContent());
        }

        if (!ensureConfiguredAssetPacks(config.getApplicationAssetPackNames())) return false;

        File stagingDirectory = new File(applicationDirectory.getParentFile(), ".win2apk-asset-install-staging");
        FileUtils.delete(stagingDirectory);
        if (!stagingDirectory.mkdirs()) return false;

        // Install-time Play Asset Packs are exposed through the application's
        // AssetManager once the split is installed. The same extraction path
        // remains usable for the legacy bundled-asset mode.
        if (!extractConfiguredApplicationAsset(config, stagingDirectory)) {
            FileUtils.delete(stagingDirectory);
            return false;
        }

        if (applicationDirectory.exists() && !FileUtils.delete(applicationDirectory)) {
            FileUtils.delete(stagingDirectory);
            return false;
        }
        if (!stagingDirectory.renameTo(applicationDirectory)) {
            FileUtils.delete(stagingDirectory);
            return false;
        }

        File markerTemporary = new File(applicationDirectory.getParentFile(), INSTALL_MARKER + ".tmp");
        if (!FileUtils.writeString(markerTemporary, "asset-packs-extracted\n")) return false;
        if (!markerTemporary.renameTo(marker)) return false;

        removeConfiguredAssetPacks(config.getApplicationAssetPackNames());
        cleanupLocalTestingSource();

        File shortcutFile = config.getShortcutFile(container);
        File shortcutDirectory = shortcutFile.getParentFile();
        if (shortcutDirectory != null && !shortcutDirectory.isDirectory() && !shortcutDirectory.mkdirs()) return false;
        return FileUtils.writeString(shortcutFile, config.getShortcutContent());
    }

    private boolean ensureConfiguredAssetPacks(String[] packNames) {
        if (packNames.length == 0) return true;

        List<String> names = Arrays.asList(packNames);
        AssetPackManager manager = AssetPackManagerFactory.getInstance(context);
        long deadline = System.currentTimeMillis() + ASSET_PACK_TIMEOUT_MS;
        try {
            Log.i(TAG, "requesting on-demand asset packs=" + names);
            Tasks.await(manager.fetch(names), ASSET_PACK_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            while (System.currentTimeMillis() < deadline) {
                AssetPackStates states = Tasks.await(manager.getPackStates(names), ASSET_PACK_TIMEOUT_MS, TimeUnit.MILLISECONDS);
                boolean complete = true;
                for (String name : names) {
                    AssetPackState state = states.packStates().get(name);
                    if (state == null) {
                        Log.e(TAG, "pack=" + name + " state=null");
                        return false;
                    }
                    Log.i(TAG, "pack=" + name + " status=" + state.status()
                            + " downloaded=" + state.bytesDownloaded()
                            + " total=" + state.totalBytesToDownload()
                            + " error=" + state.errorCode());
                    if (state.status() == AssetPackStatus.FAILED || state.status() == AssetPackStatus.CANCELED) {
                        Log.e(TAG, "pack=" + name + " failed with status=" + state.status()
                                + " error=" + state.errorCode());
                        return false;
                    }
                    if (state.status() != AssetPackStatus.COMPLETED) complete = false;
                }
                if (complete) return true;
                Thread.sleep(ASSET_PACK_POLL_MS);
            }
            Log.e(TAG, "timed out waiting for asset packs=" + names);
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            Log.e(TAG, "asset pack wait interrupted", e);
        }
        catch (ExecutionException | TimeoutException | RuntimeException e) {
            Log.e(TAG, "asset pack request failed", e);
        }
        return false;
    }

    private void removeConfiguredAssetPacks(String[] packNames) {
        if (packNames.length == 0) return;

        AssetPackManager manager = AssetPackManagerFactory.getInstance(context);
        for (String packName : packNames) {
            try {
                if (manager.getPackLocation(packName) == null) {
                    Log.i(TAG, "source asset pack already absent=" + packName);
                    continue;
                }
                Log.i(TAG, "removing source asset pack=" + packName);
                Tasks.await(manager.removePack(packName), ASSET_PACK_TIMEOUT_MS, TimeUnit.MILLISECONDS);
                Log.i(TAG, "removed source asset pack=" + packName);
            }
            catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                Log.e(TAG, "remove interrupted for pack=" + packName, e);
                return;
            }
            catch (ExecutionException | TimeoutException | RuntimeException e) {
                Log.e(TAG, "remove failed for pack=" + packName, e);
            }
        }
    }

    /**
     * bundletool local testing stages source APKs in this app-owned directory.
     * It is safe to remove only after the extracted application and its marker
     * have been committed, because the runtime no longer reads those APKs.
     */
    private void cleanupLocalTestingSource() {
        File externalFilesDirectory = context.getExternalFilesDir(null);
        if (externalFilesDirectory == null) {
            Log.w(TAG, "local-testing cleanup skipped: external files directory is null");
            return;
        }

        File localTestingDirectory = new File(externalFilesDirectory, "local_testing");
        if (!localTestingDirectory.exists()) {
            Log.i(TAG, "local-testing source absent: " + localTestingDirectory.getAbsolutePath());
            return;
        }

        boolean removed = FileUtils.delete(localTestingDirectory);
        Log.i(TAG, "local-testing source cleanup path=" + localTestingDirectory.getAbsolutePath()
                + " removed=" + removed + " existsAfter=" + localTestingDirectory.exists());
    }

    private boolean extractConfiguredApplicationAsset(CoreConfig config, File destination) {
        String[] assetParts = config.getApplicationAssetParts();
        String[] packNames = config.getApplicationAssetPackNames();
        if (assetParts.length == 1) {
            try (InputStream source = openConfiguredAssetPart(assetParts[0], packNames, 0)) {
                return TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, source, destination);
            }
            catch (IOException e) {
                Log.e(TAG, "unable to open application asset part=" + assetParts[0], e);
                return false;
            }
        }

        Vector<InputStream> streams = new Vector<>();
        try {
            for (int i = 0; i < assetParts.length; i++) {
                streams.add(openConfiguredAssetPart(assetParts[i], packNames, i));
            }
            try (InputStream source = new SequenceInputStream(streams.elements())) {
                return TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, source, destination);
            }
        }
        catch (IOException e) {
            for (InputStream stream : streams) {
                try { stream.close(); }
                catch (IOException ignored) {}
            }
            return false;
        }
    }

    private InputStream openConfiguredAssetPart(String assetPart, String[] packNames, int partIndex) throws IOException {
        if (packNames.length > partIndex) {
            AssetPackManager manager = AssetPackManagerFactory.getInstance(context);
            AssetPackLocation location = manager.getPackLocation(packNames[partIndex]);
            if (location != null && location.assetsPath() != null) {
                File file = new File(location.assetsPath(), assetPart);
                Log.i(TAG, "opening on-demand asset path=" + file.getAbsolutePath()
                        + " exists=" + file.isFile() + " size=" + file.length());
                return new FileInputStream(file);
            }
            Log.w(TAG, "on-demand asset location unavailable for pack=" + packNames[partIndex]);
        }
        return context.getAssets().open(assetPart);
    }

    public void duplicateContainerAsync(Container container, Runnable callback) {
        final Handler handler = new Handler();
        Executors.newSingleThreadExecutor().execute(() -> {
            duplicateContainer(container);
            handler.post(callback);
        });
    }

    public void removeContainerAsync(Container container, Runnable callback) {
        final Handler handler = new Handler();
        Executors.newSingleThreadExecutor().execute(() -> {
            removeContainer(container);
            handler.post(callback);
        });
    }

    private Container createContainer(JSONObject data) {
        try {
            int id = maxContainerId + 1;
            data.put("id", id);

            File containerDir = new File(homeDir, RootFS.USER+"-"+id);
            if (!containerDir.mkdirs()) return null;

            Container container = new Container(id);
            container.setRootDir(containerDir);
            container.loadData(data);

            boolean isMainWineVersion = !data.has("wineVersion") || WineInfo.isMainWineVersion(data.getString("wineVersion"));
            if (!isMainWineVersion) container.setWineVersion(data.getString("wineVersion"));

            if (!extractContainerPatternFile(container.getWineVersion(), containerDir)) {
                FileUtils.delete(containerDir);
                return null;
            }

            container.saveData();
            maxContainerId++;
            containers.add(container);
            return container;
        }
        catch (JSONException e) {}
        return null;
    }

    private void duplicateContainer(Container srcContainer) {
        int id = maxContainerId + 1;

        File dstDir = new File(homeDir, RootFS.USER+"-"+id);
        if (!dstDir.mkdirs()) return;

        if (!FileUtils.copy(srcContainer.getRootDir(), dstDir, (file) -> FileUtils.chmod(file, 0771))) {
            FileUtils.delete(dstDir);
            return;
        }

        Container dstContainer = new Container(id);
        dstContainer.setRootDir(dstDir);
        dstContainer.setName(srcContainer.getName()+" ("+context.getString(R.string.copy)+")");
        dstContainer.setScreenSize(srcContainer.getScreenSize());
        dstContainer.setEnvVars(srcContainer.getEnvVars());
        dstContainer.setCPUList(srcContainer.getCPUList());
        dstContainer.setCPUListWoW64(srcContainer.getCPUListWoW64());
        dstContainer.setGraphicsDriver(srcContainer.getGraphicsDriver());
        dstContainer.setGraphicsDriverConfig(srcContainer.getGraphicsDriverConfig());
        dstContainer.setDXWrapper(srcContainer.getDXWrapper());
        dstContainer.setDXWrapperConfig(srcContainer.getDXWrapperConfig());
        dstContainer.setAudioDriver(srcContainer.getAudioDriver());
        dstContainer.setAudioDriverConfig(srcContainer.getAudioDriverConfig());
        dstContainer.setWinComponents(srcContainer.getWinComponents());
        dstContainer.setDrives(srcContainer.getDrives());
        dstContainer.setHUDMode(srcContainer.getHUDMode());
        dstContainer.setStartupSelection(srcContainer.getStartupSelection());
        dstContainer.setBox64Preset(srcContainer.getBox64Preset());
        dstContainer.setDesktopTheme(srcContainer.getDesktopTheme());
        dstContainer.saveData();

        maxContainerId++;
        containers.add(dstContainer);
    }

    private void removeContainer(Container container) {
        if (FileUtils.delete(container.getRootDir())) containers.remove(container);
    }

    private boolean installTestApp(Container container) {
        File destination = new File(container.getRootDir(), TEST_APP_PATH);
        if (!destination.isDirectory() && !destination.mkdirs()) return false;
        return TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, context, TEST_APP_ASSET, destination);
    }

    private boolean createTestAppShortcut(Container container) {
        File desktopDir = new File(container.getUserDir(), "Desktop");
        if (!desktopDir.isDirectory() && !desktopDir.mkdirs()) return false;

        File shortcutFile = new File(desktopDir, TEST_APP_NAME+".desktop");
        String content = "[Desktop Entry]\n"
                + "Name="+TEST_APP_NAME+"\n"
                + "Exec=wine C:\\\\Win2APKTest\\\\Win2APKTest.exe\n"
                + "Type=Application\n";
        return FileUtils.writeString(shortcutFile, content);
    }

    public ArrayList<Shortcut> loadShortcuts(Shortcut selectedFolder) {
        ArrayList<Shortcut> shortcuts = new ArrayList<>();

        if (selectedFolder != null) {
            File[] files = selectedFolder.file.listFiles();
            if (files != null) {
                for (File file : files) {
                    if (file.getName().endsWith(".desktop") || file.isDirectory()) {
                        shortcuts.add(new Shortcut(selectedFolder.container, file));
                    }
                }
            }
        }
        else {
            for (Container container : containers) {
                File desktopDir = new File(container.getUserDir(), "Desktop");
                File[] files = desktopDir.listFiles();
                if (files != null) {
                    for (File file : files) {
                        if (file.getName().endsWith(".desktop") || file.isDirectory()) {
                            shortcuts.add(new Shortcut(container, file));
                        }
                    }
                }
            }
        }

        shortcuts.sort((a, b) -> {
            int value = Boolean.compare(b.file.isDirectory(), a.file.isDirectory());
            if (value == 0) value = a.name.compareTo(b.name);
            return value;
        });
        return shortcuts;
    }

    public ArrayList<FileInfo> loadFiles(Container container, FileInfo parent) {
        ArrayList<FileInfo> fileInfos = new ArrayList<>();

        if (parent != null) {
            fileInfos = parent.list();
        }
        else {
            String rootPath = container.getRootDir().getPath();
            fileInfos.add(new FileInfo(container, "C:", rootPath+"/.wine/drive_c", FileInfo.Type.DRIVE));
            for (Drive drive : container.drivesIterator()) {
                fileInfos.add(new FileInfo(container, drive.letter+":", drive.path, FileInfo.Type.DRIVE));
            }

            File userDir = container.getUserDir();
            File documentsDir = new File(userDir, "Documents");
            File favoritesDir = new File(userDir, "Favorites");

            fileInfos.add(new FileInfo(container, documentsDir.getName(), documentsDir.getPath(), FileInfo.Type.DIRECTORY));
            fileInfos.add(new FileInfo(container, favoritesDir.getName(), favoritesDir.getPath(), FileInfo.Type.DIRECTORY));

            Collections.sort(fileInfos);
        }
        return fileInfos;
    }

    public int getNextContainerId() {
        return maxContainerId + 1;
    }

    public Container getContainerById(int id) {
        for (Container container : containers) if (container.id == id) return container;
        return null;
    }

    private void copyCommonDlls(String srcName, String dstName, JSONObject commonDlls, File containerDir) throws JSONException {
        File srcDir = new File(RootFS.find(context).getRootDir(), "/opt/wine/lib/wine/"+srcName);
        JSONArray dlnames = commonDlls.getJSONArray(dstName);

        for (int i = 0; i < dlnames.length(); i++) {
            String dlname = dlnames.getString(i);
            File dstFile = new File(containerDir, ".wine/drive_c/windows/"+dstName+"/"+dlname);
            FileUtils.copy(new File(srcDir, dlname), dstFile);
        }
    }

    private boolean extractContainerPatternFile(String wineVersion, File containerDir) {
        if (WineInfo.isMainWineVersion(wineVersion)) {
            boolean result = TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, context, "container_pattern.tzst", containerDir);

            if (result) {
                try {
                    JSONObject commonDlls = new JSONObject(FileUtils.readString(context, "common_dlls.json"));
                    copyCommonDlls("x86_64-windows", "system32", commonDlls, containerDir);
                    copyCommonDlls("i386-windows", "syswow64", commonDlls, containerDir);
                }
                catch (JSONException e) {
                    return false;
                }
            }

            return result;
        }
        else {
            File installedWineDir = RootFS.find(context).getInstalledWineDir();
            WineInfo wineInfo = WineInfo.fromIdentifier(context, wineVersion);
            File file = new File(installedWineDir, "container-pattern-"+wineInfo.fullVersion()+".tzst");
            return TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, file, containerDir);
        }
    }
}

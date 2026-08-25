package com.winlator.core;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Environment;

import androidx.preference.PreferenceManager;

import com.winlator.box64.Box64Preset;
import com.winlator.container.Container;
import com.winlator.container.GraphicsDrivers;
import com.winlator.widget.FrameRating;
import com.winlator.SettingsFragment;
import com.winlator.widget.InputControlsView;

import org.json.JSONException;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.nio.charset.StandardCharsets;

/** Configuration for the headless Winlator Core startup flow. */
public final class CoreConfig {
    private final JSONObject root;

    private CoreConfig(JSONObject root) {
        this.root = root;
    }

    public static CoreConfig load(Context context) throws JSONException {
        byte[] data = FileUtils.read(context, "win2apk.json");
        if (data == null) throw new JSONException("Unable to read win2apk.json");
        return new CoreConfig(new JSONObject(new String(data, StandardCharsets.UTF_8)));
    }

    private JSONObject section(String name) {
        return root.optJSONObject(name) != null ? root.optJSONObject(name) : new JSONObject();
    }

    private String string(JSONObject object, String key, String fallback) {
        return object.optString(key, fallback);
    }

    private boolean bool(JSONObject object, String key, boolean fallback) {
        return object.has(key) ? object.optBoolean(key, fallback) : fallback;
    }

    private JSONObject containerSection() {
        return section("container");
    }

    private JSONObject appSection() {
        return section("app");
    }

    private JSONObject shortcutSection() {
        return section("shortcut");
    }

    private JSONObject startupSection() {
        return section("startup");
    }

    private JSONObject runtimeSection() {
        return section("runtime");
    }

    public boolean isCoreMode() {
        return bool(startupSection(), "coreMode", true);
    }

    public boolean isAutoLaunchEnabled() {
        return bool(startupSection(), "autoLaunch", true);
    }

    public boolean shouldRequestStoragePermission() {
        return bool(startupSection(), "requestStoragePermission", false);
    }

    public boolean closeCoreWhenApplicationExits() {
        return bool(startupSection(), "closeCoreWhenApplicationExits", true);
    }

    public String getLoadingText() {
        return string(startupSection(), "loadingText", "Preparing application...");
    }

    /**
     * Applies the runtime values from the build-time JSON to the preferences
     * consumed by the existing Winlator execution components.
     */
    public void applyRuntimePreferences(Context context) {
        JSONObject runtime = runtimeSection();
        SharedPreferences.Editor editor = PreferenceManager.getDefaultSharedPreferences(context).edit();

        if (runtime.has("appTheme")) {
            String theme = string(runtime, "appTheme", "dark");
            editor.putInt("app_theme", "light".equalsIgnoreCase(theme) ? SettingsFragment.APP_THEME_LIGHT : SettingsFragment.APP_THEME_DARK);
        }
        putOptionalString(editor, runtime, "soundfont", "soundfont");
        putOptionalString(editor, runtime, "midiInputDevice", "midi_input_device");
        putOptionalString(editor, runtime, "box64Version", "box64_version");
        putOptionalString(editor, runtime, "wineDebugChannels", "wine_debug_channels");
        putOptionalString(editor, runtime, "gamepadModel", "gamepad_model");
        putOptionalString(editor, runtime, "logFile", "log_file");

        if (runtime.has("languageIndex")) editor.putInt("lc_index", runtime.optInt("languageIndex", -1));
        if (runtime.has("box64Logs")) editor.putInt("box64_logs", runtime.optInt("box64Logs", 0));
        if (runtime.has("cursorColor")) editor.putInt("cursor_color", runtime.optInt("cursorColor", 0xffffff));
        if (runtime.has("cursorScale")) editor.putFloat("cursor_scale", (float)runtime.optDouble("cursorScale", 1.0));
        if (runtime.has("cursorSpeed")) editor.putFloat("cursor_speed", (float)runtime.optDouble("cursorSpeed", 1.0));
        if (runtime.has("overlayOpacity")) editor.putFloat("overlay_opacity", (float)runtime.optDouble("overlayOpacity", InputControlsView.DEFAULT_OVERLAY_OPACITY));
        if (runtime.has("saveLogsToFile")) editor.putBoolean("save_logs_to_file", runtime.optBoolean("saveLogsToFile", false));
        if (runtime.has("useAndroidClipboardOnWine")) editor.putBoolean("use_android_clipboard_on_wine", runtime.optBoolean("useAndroidClipboardOnWine", false));
        if (runtime.has("enableWineDebug")) editor.putBoolean("enable_wine_debug", runtime.optBoolean("enableWineDebug", false));
        if (runtime.has("openAndroidBrowserFromWine")) editor.putBoolean("open_android_browser_from_wine", runtime.optBoolean("openAndroidBrowserFromWine", true));
        if (runtime.has("capturePointerOnExternalMouse")) editor.putBoolean("capture_pointer_on_external_mouse", runtime.optBoolean("capturePointerOnExternalMouse", true));
        if (runtime.has("moveCursorToTouchpoint")) editor.putBoolean("move_cursor_to_touchpoint", runtime.optBoolean("moveCursorToTouchpoint", false));

        editor.apply();
    }

    private void putOptionalString(SharedPreferences.Editor editor, JSONObject object, String jsonKey, String preferenceKey) {
        if (!object.has(jsonKey)) return;
        String value = object.optString(jsonKey, "").trim();
        if (value.isEmpty()) editor.remove(preferenceKey);
        else editor.putString(preferenceKey, value);
    }

    public String getApplicationAsset() {
        return string(appSection(), "asset", "test_app.tzst");
    }

    public String getApplicationAssetPackName() {
        return string(appSection(), "assetPackName", "").trim();
    }

    public String[] getApplicationAssetParts() {
        JSONArray parts = appSection().optJSONArray("assetParts");
        if (parts == null || parts.length() == 0) return new String[]{getApplicationAsset()};

        String[] result = new String[parts.length()];
        for (int i = 0; i < parts.length(); i++) result[i] = parts.optString(i, "");
        return result;
    }

    public String[] getApplicationAssetPackNames() {
        JSONArray packs = appSection().optJSONArray("assetPackNames");
        if (packs == null || packs.length() == 0) {
            String legacyPack = getApplicationAssetPackName();
            return legacyPack.isEmpty() ? new String[0] : new String[]{legacyPack};
        }

        String[] result = new String[packs.length()];
        for (int i = 0; i < packs.length(); i++) result[i] = packs.optString(i, "");
        return result;
    }

    public JSONObject createContainerData(Context context) throws JSONException {
        JSONObject config = containerSection();
        JSONObject data = new JSONObject();

        data.put("name", string(config, "name", "Container-1"));
        data.put("screenSize", string(config, "screenSize", Container.DEFAULT_SCREEN_SIZE));
        data.put("envVars", string(config, "envVars", Container.DEFAULT_ENV_VARS));
        data.put("cpuList", resolveCPUList(context, string(config, "cpuList", "auto")));
        data.put("cpuListWoW64", resolveCPUList(context, string(config, "cpuListWoW64", "auto")));
        data.put("graphicsDriver", resolveGraphicsDriver(context, string(config, "graphicsDriver", "auto")));
        data.put("graphicsDriverConfig", string(config, "graphicsDriverConfig", ""));
        data.put("dxwrapper", string(config, "dxwrapper", Container.DEFAULT_DXWRAPPER));
        data.put("dxwrapperConfig", string(config, "dxwrapperConfig", ""));
        data.put("audioDriver", string(config, "audioDriver", Container.DEFAULT_AUDIO_DRIVER));
        data.put("audioDriverConfig", string(config, "audioDriverConfig", ""));
        data.put("wincomponents", string(config, "wincomponents", Container.DEFAULT_WINCOMPONENTS));
        data.put("drives", resolveDrives(context, string(config, "drives", Container.DEFAULT_DRIVES)));
        data.put("hudMode", config.optInt("hudMode", FrameRating.Mode.DISABLED.ordinal()));
        data.put("startupSelection", config.optInt("startupSelection", Container.STARTUP_SELECTION_ESSENTIAL));
        data.put("box64Preset", string(config, "box64Preset", Box64Preset.DEFAULT));
        data.put("desktopTheme", string(config, "desktopTheme", "LIGHT,IMAGE,#0277bd"));

        String wineVersion = string(config, "wineVersion", "");
        if (!wineVersion.isEmpty()) data.put("wineVersion", wineVersion);
        return data;
    }

    private String resolveCPUList(Context context, String value) {
        return value.equalsIgnoreCase("auto") ? Container.getFallbackCPUList() : value;
    }

    private String resolveGraphicsDriver(Context context, String value) {
        return value.equalsIgnoreCase("auto") ? GraphicsDrivers.getDefaultDriver(context) : value;
    }

    private String resolveDrives(Context context, String value) {
        String downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).getPath();
        return value.replace("${DOWNLOADS}", downloads).replace("${INTERNAL_STORAGE}", AppUtils.INTERNAL_STORAGE);
    }

    public File getApplicationDirectory(Container container) {
        String directory = string(appSection(), "windowsDirectory", "Win2APKTest");
        return new File(container.getRootDir(), ".wine/drive_c/"+directory);
    }

    public File getShortcutFile(Container container) {
        String desktopFile = string(shortcutSection(), "desktopFile", "Win2APKTest.desktop");
        return new File(new File(container.getUserDir(), "Desktop"), desktopFile);
    }

    public String getShortcutContent() {
        JSONObject app = appSection();
        JSONObject shortcut = shortcutSection();
        String name = string(shortcut, "name", string(app, "folderName", "Application"));
        String windowsPath = string(app, "windowsPath", "C:\\Win2APKTest");
        String executable = string(app, "executable", "Win2APKTest.exe");
        String execPath = windowsPath + "\\" + executable;
        StringBuilder content = new StringBuilder();
        content.append("[Desktop Entry]\n");
        content.append("Name=").append(name).append("\n");
        content.append("Exec=wine ").append(StringUtils.escapeDOSPath(execPath)).append("\n");
        content.append("Type=Application\n");

        String execArguments = string(shortcut, "execArguments", "");
        boolean forceFullscreen = bool(shortcut, "forceFullscreen", false);
        if (!execArguments.isEmpty() || forceFullscreen) {
            content.append("\n[Extra Data]\n");
            if (!execArguments.isEmpty()) content.append("execArgs=").append(execArguments).append("\n");
            if (forceFullscreen) content.append("forceFullscreen=1\n");
        }
        return content.toString();
    }
}

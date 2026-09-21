package com.winlator;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.net.Uri;
import android.os.Bundle;
import android.view.MenuItem;

import androidx.annotation.IntRange;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.view.GravityCompat;
import androidx.drawerlayout.widget.DrawerLayout;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.preference.PreferenceManager;

import com.google.android.material.navigation.NavigationView;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.winlator.container.ContainerManager;
import com.winlator.contentdialog.AboutDialog;
import com.winlator.core.AppUtils;
import com.winlator.core.AssetPackDiagnostics;
import com.winlator.core.Callback;
import com.winlator.core.CoreConfig;
import com.winlator.core.LocaleHelper;
import com.winlator.core.PreloaderDialog;
import com.winlator.xenvironment.RootFSInstaller;

public class MainActivity extends AppCompatActivity implements NavigationView.OnNavigationItemSelectedListener {
    public static final boolean DEBUG_MODE = false; // FIXME change to false
    public static final @IntRange(from = 1, to = 19) byte CONTAINER_PATTERN_COMPRESSION_LEVEL = 9;
    public static final byte PERMISSION_WRITE_EXTERNAL_STORAGE_REQUEST_CODE = 1;
    public static final byte OPEN_FILE_REQUEST_CODE = 2;
    public static final byte EDIT_INPUT_CONTROLS_REQUEST_CODE = 3;
    public static final byte OPEN_DIRECTORY_REQUEST_CODE = 4;
    private static final String DEFAULT_CONTAINER_CREATED = "default_container_created";
    private DrawerLayout drawerLayout;
    public final PreloaderDialog preloaderDialog = new PreloaderDialog(this);
    private boolean editInputControls = false;
    private int selectedProfileId;
    private Callback<Uri> openFileCallback;
    private SharedPreferences preferences;
    private Fragment currentFragment;
    private CoreConfig coreConfig;
    private boolean coreMode;
    private boolean permanentNavigation;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        Exception coreConfigError = null;
        try {
            coreConfig = CoreConfig.load(this);
            coreMode = coreConfig.isCoreMode();
            if (coreMode) coreConfig.applyRuntimePreferences(this);
        }
        catch (Exception e) {
            coreConfigError = e;
        }

        AppUtils.setActivityTheme(this);
        super.onCreate(savedInstanceState);

        if (coreConfigError != null) {
            showCoreError("Unable to load win2apk.json: "+coreConfigError.getMessage());
            return;
        }

        AssetPackDiagnostics.log(this, coreConfig);

        if (coreMode) {
            initializeCoreMode();
            return;
        }

        setContentView(R.layout.main_activity);

        drawerLayout = findViewById(R.id.DrawerLayout);
        NavigationView navigationView = findViewById(R.id.NavigationView);
        navigationView.setNavigationItemSelectedListener(this);
        permanentNavigation = getResources().getBoolean(R.bool.use_permanent_navigation);
        if (permanentNavigation && drawerLayout != null) {
            drawerLayout.setDrawerLockMode(DrawerLayout.LOCK_MODE_LOCKED_OPEN, GravityCompat.START);
        }

        setSupportActionBar(findViewById(R.id.Toolbar));
        ActionBar actionBar = getSupportActionBar();

        preferences = PreferenceManager.getDefaultSharedPreferences(this);

        Intent intent = getIntent();
        editInputControls = intent.getBooleanExtra("edit_input_controls", false);
        if (editInputControls) {
            selectedProfileId = intent.getIntExtra("selected_profile_id", 0);
            updateNavigationIcon(true);
            onNavigationItemSelected(navigationView.getMenu().findItem(R.id.menu_item_input_controls));
            navigationView.setCheckedItem(R.id.menu_item_input_controls);
        }
        else {
            boolean showShortcutsFirst = preferences.getBoolean("show_shortcuts_first", false);
            int selectedMenuItemId = intent.getIntExtra("selected_menu_item_id", 0);
            int menuItemId = selectedMenuItemId > 0 ? selectedMenuItemId : (showShortcutsFirst ? R.id.menu_item_shortcuts : R.id.menu_item_containers);

            updateNavigationIcon(false);
            onNavigationItemSelected(navigationView.getMenu().findItem(menuItemId));
            navigationView.setCheckedItem(menuItemId);
            if (!requestAppPermissions()) RootFSInstaller.installIfNeeded(this, ready -> ensureDefaultContainer(ready));

            int containerId = intent.getIntExtra("container_id", 0);
            String startPath = intent.getStringExtra("start_path");
            if (containerId > 0 && startPath != null) {
                showFragment(new ContainerFileManagerFragment(containerId, startPath));
            }
        }
    }

    @Override
    protected void attachBaseContext(Context newBase) {
        super.attachBaseContext(LocaleHelper.setSystemLocale(newBase));
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSION_WRITE_EXTERNAL_STORAGE_REQUEST_CODE) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                if (coreMode) RootFSInstaller.installIfNeeded(this, this::onCoreRootFSReady, true);
                else RootFSInstaller.installIfNeeded(this, ready -> ensureDefaultContainer(ready));
            }
            else finish();
        }
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == MainActivity.OPEN_FILE_REQUEST_CODE && resultCode == Activity.RESULT_OK) {
            if (openFileCallback != null) {
                openFileCallback.call(data.getData());
                openFileCallback = null;
            }
        }
    }

    @Override
    public void onConfigurationChanged(@NonNull Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        if ((newConfig.orientation == Configuration.ORIENTATION_LANDSCAPE ||
            newConfig.orientation == Configuration.ORIENTATION_PORTRAIT) && currentFragment instanceof BaseFileManagerFragment) {
            ((BaseFileManagerFragment)currentFragment).onOrientationChanged();
        }
    }

    @Override
    public void onBackPressed() {
        if (currentFragment != null && currentFragment.isVisible()) {
            if (currentFragment instanceof BaseFileManagerFragment) {
                BaseFileManagerFragment fileManagerFragment = (BaseFileManagerFragment)currentFragment;
                if (fileManagerFragment.onBackPressed()) return;
            }
            else if (currentFragment instanceof ContainersFragment) {
                finish();
            }
        }

        showFragment(new ContainersFragment());
    }

    public void setOpenFileCallback(Callback<Uri> openFileCallback) {
        this.openFileCallback = openFileCallback;
    }

    private boolean requestAppPermissions() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED) return false;

        String[] permissions = new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE, Manifest.permission.READ_EXTERNAL_STORAGE};
        ActivityCompat.requestPermissions(this, permissions, PERMISSION_WRITE_EXTERNAL_STORAGE_REQUEST_CODE);
        return true;
    }

    private void ensureDefaultContainer(boolean rootFsReady) {
        if (!rootFsReady) return;

        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(this);
        ContainerManager manager = new ContainerManager(this);
        if (preferences.getBoolean(DEFAULT_CONTAINER_CREATED, false)) return;

        if (!manager.getContainers().isEmpty()) {
            preferences.edit().putBoolean(DEFAULT_CONTAINER_CREATED, true).apply();
            return;
        }

        preloaderDialog.show(R.string.creating_container);
        manager.createDefaultContainerAsync((container) -> {
            preloaderDialog.close();
            if (container != null) {
                preferences.edit().putBoolean(DEFAULT_CONTAINER_CREATED, true).apply();
                if (currentFragment instanceof ContainersFragment) showFragment(new ContainersFragment());
            }
        });
    }

    private void initializeCoreMode() {
        setContentView(R.layout.loading_screen);

        if (coreConfig.shouldRequestStoragePermission()) {
            if (!requestAppPermissions()) RootFSInstaller.installIfNeeded(this, this::onCoreRootFSReady, true);
        }
        else RootFSInstaller.installIfNeeded(this, this::onCoreRootFSReady, true);
    }

    private void onCoreRootFSReady(boolean ready) {
        if (!ready) {
            showCoreError("Unable to prepare the filesystem.");
            return;
        }

        ContainerManager manager = new ContainerManager(this);
        manager.createConfiguredContainerAsync(coreConfig, container -> {
            if (container == null) {
                showCoreError("Unable to create the configured container or shortcut.");
                return;
            }

            if (!coreConfig.isAutoLaunchEnabled()) {
                showCoreError("Automatic launch is disabled in win2apk.json.");
                return;
            }

            Intent intent = new Intent(this, XServerDisplayActivity.class);
            intent.putExtra("container_id", container.id);
            intent.putExtra("shortcut_path", coreConfig.getShortcutFile(container).getPath());
            intent.putExtra("core_mode", true);
            intent.putExtra("core_close_on_exit", coreConfig.closeCoreWhenApplicationExits());
            startActivity(intent);
            finish();
        });
    }

    private void showCoreError(String message) {
        new MaterialAlertDialogBuilder(this)
                .setTitle("Winlator Core error")
                .setMessage(message != null ? message : "Unknown error")
                .setCancelable(false)
                .setPositiveButton(android.R.string.ok, (dialog, which) -> finish())
                .show();
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem menuItem) {
        int itemId = menuItem.getItemId();
        if (itemId == R.id.menu_item_add ||
            itemId == R.id.menu_item_home ||
            itemId == R.id.menu_item_view_style ||
            itemId == R.id.menu_item_new_folder) {
            return super.onOptionsItemSelected(menuItem);
        }
        else {
            if (editInputControls) {
                setResult(RESULT_OK);
                finish();
            }
            else {
                if (currentFragment instanceof BaseFileManagerFragment) {
                    BaseFileManagerFragment fileManagerFragment = (BaseFileManagerFragment)currentFragment;
                    if (fileManagerFragment.onOptionsMenuClicked()) return true;
                }
                if (!permanentNavigation) drawerLayout.openDrawer(GravityCompat.START);
            }
            return true;
        }
    }

    @Override
    public boolean onNavigationItemSelected(@NonNull MenuItem item) {
        FragmentManager fragmentManager = getSupportFragmentManager();
        if (fragmentManager.getBackStackEntryCount() > 0) {
            fragmentManager.popBackStack(null, FragmentManager.POP_BACK_STACK_INCLUSIVE);
        }

        switch (item.getItemId()) {
            case R.id.menu_item_shortcuts:
                preferences.edit().putBoolean("show_shortcuts_first", true).apply();
                showFragment(new ShortcutsFragment());
                break;
            case R.id.menu_item_containers:
                preferences.edit().putBoolean("show_shortcuts_first", false).apply();
                showFragment(new ContainersFragment());
                break;
            case R.id.menu_item_input_controls:
                showFragment(new InputControlsFragment(selectedProfileId));
                break;
            case R.id.menu_item_settings:
                showFragment(new SettingsFragment());
                break;
            case R.id.menu_item_about:
                (new AboutDialog(this)).show();
                break;
        }
        return true;
    }

    public void showFragment(Fragment fragment) {
        FragmentManager fragmentManager = getSupportFragmentManager();
        fragmentManager.beginTransaction()
            .replace(R.id.FLFragmentContainer, fragment)
            .commit();

        updateNavigationIcon(false);
        if (!permanentNavigation) drawerLayout.closeDrawer(GravityCompat.START);
        currentFragment = fragment;
    }

    public void updateNavigationIcon(boolean showBack) {
        ActionBar actionBar = getSupportActionBar();
        if (actionBar == null) return;

        if (showBack || editInputControls) {
            actionBar.setDisplayHomeAsUpEnabled(true);
            actionBar.setHomeAsUpIndicator(R.drawable.icon_action_bar_back);
        }
        else if (permanentNavigation) {
            actionBar.setDisplayHomeAsUpEnabled(false);
        }
        else {
            actionBar.setDisplayHomeAsUpEnabled(true);
            actionBar.setHomeAsUpIndicator(R.drawable.icon_action_bar_menu);
        }
    }
}

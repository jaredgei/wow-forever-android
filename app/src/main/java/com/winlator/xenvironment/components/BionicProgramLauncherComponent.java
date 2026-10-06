package com.winlator.xenvironment.components;

import android.app.Service;
import android.content.Context;
import android.content.SharedPreferences;
import android.media.Image;
import android.net.ConnectivityManager;
import android.net.InetAddresses;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.provider.ContactsContract;
import android.util.Log;

import androidx.annotation.NonNull;

import com.winlator.PrefManager;

import app.gamenative.utils.LsfgVkManager;
import com.winlator.box86_64.Box86_64Preset;
import com.winlator.box86_64.Box86_64PresetManager;
import com.winlator.container.Container;
import com.winlator.container.ContainerOverlay;
import com.winlator.container.Shortcut;
import com.winlator.contents.ContentProfile;
import com.winlator.contents.ContentsManager;
import com.winlator.core.Callback;
import com.winlator.core.DefaultVersion;
import com.winlator.core.envvars.EnvVarRedaction;
import com.winlator.core.envvars.EnvVars;
import com.winlator.core.FileUtils;
import com.winlator.core.GPUInformation;
import com.winlator.core.ProcessHelper;
import com.winlator.core.SharedComponents;
import com.winlator.core.TarCompressorUtils;
import com.winlator.core.WineInfo;
import com.winlator.fexcore.FEXCorePreset;
import com.winlator.fexcore.FEXCorePresetManager;
import com.winlator.sysvshm.SysVSHMConnectionHandler;
import com.winlator.sysvshm.SysVSHMRequestHandler;
import com.winlator.sysvshm.SysVSharedMemory;
import com.winlator.xconnector.UnixSocketConfig;
import com.winlator.xconnector.XConnectorEpoll;
import com.winlator.xenvironment.ImageFs;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.RandomAccessFile;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;

import app.gamenative.BuildConfig;
import app.gamenative.PluviaApp;
import app.gamenative.events.AndroidEvent;

public class BionicProgramLauncherComponent extends GuestProgramLauncherComponent {
    private String guestExecutable;
    private static int pid = -1;
    private String[] bindingPaths;
    private EnvVars envVars;
    private WineInfo wineInfo;
    private String box64Version = DefaultVersion.BOX64;
    private String box64Preset = Box86_64Preset.COMPATIBILITY;
    private String fexcorePreset = FEXCorePreset.INTERMEDIATE;
    private Callback<Integer> terminationCallback;
    private static final Object lock = new Object();
    private boolean wow64Mode = true;
    private final ContentsManager contentsManager;
    private final ContentProfile wineProfile;
    private Container container;
    private File workingDir;

    public void setWineInfo(WineInfo wineInfo) {
        this.wineInfo = wineInfo;
    }
    public WineInfo getWineInfo() {
        return this.wineInfo;
    }

    public Container getContainer() { return this.container; }
    public void setContainer(Container container) { this.container = container; }

    // Resolve which libredirect shim to preload. Normally the flavor default
    // (PRELOAD_BIONIC_SO). When the container disables libredirect, modern falls
    // back to the W^X-only minimal shim (still required to run Wine on a strict
    // W^X kernel) and legacy preloads nothing. Returns null to preload nothing.
    private String resolveLibredirectPreload(ImageFs imageFs) {
        com.winlator.xenvironment.ImageFsInstaller.ensureBionicLib(environment.getContext(), imageFs.getRootDir());
        if (container != null && container.isDisableLibredirect()) {
            if (BuildConfig.MODERN_ANDROID) {
                return imageFs.getLibDir() + "/libredirect-bionic-wx-minimal.so";
            }
            return null;
        }
        return imageFs.getLibDir() + "/" + BuildConfig.PRELOAD_BIONIC_SO;
    }

    public BionicProgramLauncherComponent(ContentsManager contentsManager, ContentProfile wineProfile) {
        this.contentsManager = contentsManager;
        this.wineProfile = wineProfile;
    }

    private Runnable preUnpack;
    public void setPreUnpack(Runnable r) { this.preUnpack = r; }
    @Override
    public void start() {
        synchronized (lock) {
            stop();
            if (wineInfo.isArm64EC())
                extractEmulatorsDlls();
            else
                extractBox64Files();
            if (preUnpack != null) preUnpack.run();
            pid = execGuestProgram();
            Log.d("BionicProgramLauncherComponent", "Process " + pid + " started");
            PluviaApp.setKeepAlive(true);
        }
    }

    @Override
    public void stop() {
        synchronized (lock) {
            if (pid != -1) {
                Process.killProcess(pid);
                Log.d("BionicProgramLauncherComponent", "Stopped process " + pid);
                List<ProcessHelper.ProcessInfo> subProcesses = ProcessHelper.listSubProcesses();
                for (ProcessHelper.ProcessInfo subProcess : subProcesses) {
                    Process.killProcess(subProcess.pid);
                }
                PluviaApp.setKeepAlive(false);
                execShellCommand("wineserver -k");
            }
        }
    }

    public Callback<Integer> getTerminationCallback() {
        return terminationCallback;
    }

    public void setTerminationCallback(Callback<Integer> terminationCallback) {
        this.terminationCallback = terminationCallback;
    }

    public String getGuestExecutable() {
        return guestExecutable;
    }

    public void setGuestExecutable(String guestExecutable) {
        this.guestExecutable = guestExecutable;
    }

    public boolean isWoW64Mode() {
        return wow64Mode;
    }

    public void setWoW64Mode(boolean wow64Mode) {
        this.wow64Mode = wow64Mode;
    }

    public String[] getBindingPaths() {
        return bindingPaths;
    }

    public void setBindingPaths(String[] bindingPaths) {
        this.bindingPaths = bindingPaths;
    }

    public EnvVars getEnvVars() {
        return envVars;
    }

    public void setEnvVars(EnvVars envVars) {
        this.envVars = envVars;
    }

    public String getBox64Preset() {
        return box64Preset;
    }

    public void setBox64Preset(String box64Preset) {
        this.box64Preset = box64Preset;
    }

    public void setFEXCorePreset (String fexcorePreset) { this.fexcorePreset = fexcorePreset; }

    public File getWorkingDir() {
        return workingDir;
    }

    public void setWorkingDir(File workingDir) {
        this.workingDir = workingDir;
    }

    private int execGuestProgram() {
        ImageFs imageFs = environment.getImageFs();
        final int MAX_PLAYERS = 4;

        // Get the number of enabled players directly from ControllerManager.
        for (int i = 0; i < MAX_PLAYERS; i++) {
            String memPath;
            if (i == 0) {
                // Player 1 uses the original, non-numbered path that is known to work.
                memPath = new File(imageFs.getRootDir(), "tmp/gamepad.mem").getAbsolutePath();
            } else {
                // Players 2, 3, 4 use a 1-based index.
                memPath = new File(imageFs.getRootDir(), "tmp/gamepad" + i + ".mem").getAbsolutePath();
            }

            File memFile = new File(memPath);
            memFile.getParentFile().mkdirs();
            try (RandomAccessFile raf = new RandomAccessFile(memFile, "rw")) {
                raf.setLength(64);
            } catch (IOException e) {
                Log.e("EVSHIM_HOST", "Failed to create mem file for player index "+i, e);
            }
        }
        Context context = environment.getContext();
        imageFs = ImageFs.find(context);
        File rootDir = imageFs.getRootDir();

        PrefManager.init(context);
        boolean enableBox86_64Logs = PrefManager.getBoolean("enable_box86_64_logs", false);
        boolean shareAndroidClipboard = PrefManager.getBoolean("share_android_clipboard", false);
        boolean enablePebLogs = PrefManager.getBoolean("enable_peb_logs", false);

        envVars.put("WINEMU_HOST_PKG", context.getPackageName());
        envVars.put("HOST_PKG", context.getPackageName());
        envVars.put("EVSHIM_BASE_PATH", context.getFilesDir().getAbsolutePath());

        if (shareAndroidClipboard) {
            envVars.put("WINE_FROM_ANDROID_CLIPBOARD", "1");
            envVars.put("WINE_TO_ANDROID_CLIPBOARD", "1");
        }
        if (enablePebLogs) {
            envVars.put("WINE_LOG_PEB_DATA", "1");
        }

        EnvVars envVars = new EnvVars();

        // Use the ControllerManager's dynamic count for the environment variable
        envVars.put("EVSHIM_MAX_PLAYERS", String.valueOf(MAX_PLAYERS));
        if (true) {
            envVars.put("EVSHIM_SHM_ID", 1);
        }
        addBox64EnvVars(envVars, enableBox86_64Logs);
        envVars.putAll(FEXCorePresetManager.getEnvVars(context, fexcorePreset));

        String renderer = GPUInformation.getRenderer(context);

        if (renderer.contains("Mali"))
            envVars.put("BOX64_MMAP32", "0");

        if (envVars.get("BOX64_MMAP32").equals("1") && !wineInfo.isArm64EC())
            envVars.put("WRAPPER_DISABLE_PLACED", "1");

        // Setting up essential environment variables for Wine
        envVars.put("HOME", imageFs.home_path);
        envVars.put("USER", ImageFs.USER);
        envVars.put("TMPDIR", rootDir.getPath() + "/usr/tmp");
        new File(imageFs.home_path + "/.wine/drive_c" + rootDir.getPath() + "/usr/tmp").mkdirs();
        envVars.put("DISPLAY", ":0");

        String winePath = imageFs.getWinePath() + "/bin";

        Log.d("BionicProgramLauncherComponent", "WinePath is " + winePath);

        envVars.put("PATH", winePath + ":" +
                rootDir.getPath() + "/usr/bin");
        if (BuildConfig.MODERN_ANDROID) envVars.put("REDIRECT_EXEC__PROC_SELF_EXE", winePath + "/wine");

        String ldLibraryPath = rootDir.getPath() + "/usr/lib" + ":" + "/system/lib64";
        if (BuildConfig.MODERN_ANDROID) ldLibraryPath += ":" + imageFs.getWinePath() + "/lib";
        envVars.put("LD_LIBRARY_PATH", ldLibraryPath);
        envVars.put("ANDROID_SYSVSHM_SERVER", rootDir.getPath() + UnixSocketConfig.SYSVSHM_SERVER_PATH);
        envVars.put("FONTCONFIG_PATH", rootDir.getPath() + "/usr/etc/fonts");

        envVars.put("XDG_DATA_DIRS", rootDir.getPath() + "/usr/share");
        envVars.put("XDG_CONFIG_DIRS", rootDir.getPath() + "/usr/etc/xdg");
        envVars.put("GST_PLUGIN_PATH", rootDir.getPath() + "/usr/lib/gstreamer-1.0");
        envVars.put("VK_LAYER_PATH", rootDir.getPath() + "/usr/share/vulkan/implicit_layer.d" + ":" + rootDir.getPath() + "/usr/share/vulkan/explicit_layer.d");
        envVars.put("WINE_NO_DUPLICATE_EXPLORER", "1");
        envVars.put("PREFIX", rootDir.getPath() + "/usr");
        envVars.put("WINE_DISABLE_FULLSCREEN_HACK", "1");
        envVars.put("ENABLE_UTIL_LAYER", "1");
        envVars.put("GST_PLUGIN_FEATURE_RANK", "ximagesink:3000");
        envVars.put("ALSA_CONFIG_PATH", rootDir.getPath() + "/usr/share/alsa/alsa.conf" + ":" + rootDir.getPath() + "/usr/etc/alsa/conf.d/android_aserver.conf");
        envVars.put("ALSA_PLUGIN_DIR", rootDir.getPath() + "/usr/lib/alsa-lib");
        envVars.put("OPENSSL_CONF", rootDir.getPath() + "/usr/etc/tls/openssl.cnf");
        envVars.put("SSL_CERT_FILE", rootDir.getPath() + "/usr/etc/tls/cert.pem");
        envVars.put("SSL_CERT_DIR", rootDir.getPath() + "/usr/etc/tls/certs");
        // Wine's crypt32 imports this bundle into the Windows ROOT certificate store. Without it
        // the store only holds Wine's built-in Microsoft roots: Android 14+ moved the system CAs
        // to an APEX path Wine does not scan, so TLS verification fails for anything that trusts
        // via the Windows store (e.g. the EOS SDK's websockets, winhttp/wininet callers).
        envVars.put("WINE_ADDITIONAL_CERTS_DIR", rootDir.getPath() + "/usr/etc/tls/cert.pem");
        envVars.put("WINE_X11FORCEGLX", "1");
        envVars.put("WINE_GST_NO_GL", "1");
        envVars.put("SteamGameId", "0");

        String primaryDNS = "8.8.4.4";
        ConnectivityManager connectivityManager = (ConnectivityManager) context.getSystemService(Service.CONNECTIVITY_SERVICE);
        if (connectivityManager.getActiveNetwork() != null) {
            ArrayList<InetAddress> dnsServers = new ArrayList<>(connectivityManager.getLinkProperties(connectivityManager.getActiveNetwork()).getDnsServers());

            // Check if the dnsServers list is not empty before getting an item
            if (!dnsServers.isEmpty()) {
                InetAddress selectedDNS = dnsServers.get(0);
                for (InetAddress dnsServer : dnsServers) {
                    if (dnsServer instanceof Inet4Address) {
                        selectedDNS = dnsServer;
                        break;
                    }
                }
                primaryDNS = selectedDNS.getHostAddress();
            }
        }
        envVars.put("ANDROID_RESOLV_DNS", primaryDNS);
        envVars.put("WINE_NEW_NDIS", "1");

        String ld_preload = "";
        String sysvPath = imageFs.getLibDir() + "/libandroid-sysvshm.so";
        String evshimPath = context.getApplicationInfo().nativeLibraryDir + "/libevshim.so";
        String replacePath = resolveLibredirectPreload(imageFs);

        if (new File(sysvPath).exists()) ld_preload += sysvPath;


        ld_preload += ":" + evshimPath;
        String dnsV4MappedPath = context.getApplicationInfo().nativeLibraryDir + "/libgamenative_dns_v4mapped.so";
        if (new File(dnsV4MappedPath).exists()) {
            ld_preload += ":" + dnsV4MappedPath;
        }
        if (replacePath != null) ld_preload += ":" + replacePath;

        envVars.put("LD_PRELOAD", ld_preload);
        envVars.put("EVSHIM_WINE", 1);
        envVars.put("EVSHIM_SHM_NAME", "controller-shm0");

        if (container != null && container.isFasterExternalLoading()) {
            String ffpGameDir = null;
            for (String[] drive : Container.drivesIterator(container.getDrives())) {
                if (drive[0].equals("A")) {
                    try {
                        ffpGameDir = new File(drive[1]).getCanonicalPath();
                    } catch (IOException e) {
                        ffpGameDir = drive[1];
                    }
                    break;
                }
            }
            if (ffpGameDir != null && ffpGameDir.startsWith("/storage/")
                    && !ffpGameDir.startsWith("/storage/emulated/")) {
                envVars.put("FFP_ENABLE", "1");
                envVars.put("FFP_MARKERS", "/steamapps/common/;/dosdevices/a:");
            }
        }

        // Check for specific shared memory libraries
//        if ((new File(imageFs.getLibDir(), "libandroid-sysvshm.so")).exists()){
//            ld_preload = imageFs.getLibDir() + "/libandroid-sysvshm.so";
//        }

        //String nativeDir = context.getApplicationInfo().nativeLibraryDir; // e.g. /data/app/…/lib/arm64

        // Merge any additional environment variables from external sources
        if (this.envVars != null) {
            envVars.putAll(this.envVars);
        }

        if (BuildConfig.XR_BUILD) {
            String shimPath = context.getApplicationInfo().nativeLibraryDir + "/libkgslshim.so";
            if (new File(shimPath).exists()) {
                String cur = envVars.get("LD_PRELOAD");
                envVars.put("LD_PRELOAD", cur.isEmpty() ? shimPath : shimPath + ":" + cur);
            }
        }

        if (LsfgVkManager.isSupported(container)) {
            LsfgVkManager.ensureRuntimeInstalled(environment.getContext(), container);
            LsfgVkManager.writeConfig(container);
            LsfgVkManager.applyLaunchEnv(container, envVars);
            if (LsfgVkManager.isArmed(container)) {
                final android.content.Context lsfgContext = environment.getContext();
                new Thread(() -> LsfgVkManager.prepareNativeCache(lsfgContext, container),
                    "lsfg-native-cache").start();
            }
        }

        try {
            ContainerOverlay.applyBionicLaunchEnv(context, container, envVars);
        }
        catch (IllegalStateException e) {
            Log.e("BionicProgramLauncherComponent", e.getMessage());
            return -1;
        }

        Log.d("BionicProgramLauncherComponent", "env vars are " + EnvVarRedaction.redact(envVars));

        String emulator = container.getEmulator();

        // Construct the command without Box64 to the Wine executable
        String command = "";
        String overriddenCommand = envVars.get("GUEST_PROGRAM_LAUNCHER_COMMAND");
        if (!overriddenCommand.isEmpty()) {
            String[] parts = overriddenCommand.split(";");
            for (String part : parts)
                command += part + " ";
            command = command.trim();
        }
        else {
            command = getFinalCommand(winePath, emulator, envVars, imageFs.getBinDir(), guestExecutable);
        }

        // **Maybe remove this: Set execute permissions for box64 if necessary (Glibc/Proot artifact)
        File box64File = new File(rootDir, "/usr/bin/box64");
        if (box64File.exists()) {
            FileUtils.chmod(box64File, 0755);
        }

        return ProcessHelper.exec(command, envVars.toStringArray(), workingDir != null ? workingDir : rootDir, (status) -> {
            synchronized (lock) {
                pid = -1;
            }
            if (!environment.isWinetricksRunning()) {
                PluviaApp.setKeepAlive(false);
                if (terminationCallback != null)
                    terminationCallback.call(status);
            }
        });
    }

    @NonNull
    private String getFinalCommand(String winePath, String emulator, EnvVars envVars, File binDir, String guestExecutable) {
        String command;
        if (wineInfo.isArm64EC()) {
            command = winePath + "/" + guestExecutable;
            if (emulator.toLowerCase().equals("fexcore"))
                envVars.put("HODLL", "libwow64fex.dll");
            else
                envVars.put("HODLL", "wowbox64.dll");
        }
        else
            command = binDir + "/box64 " + guestExecutable;
        return command;
    }

    private void extractBox64Files() {
        ImageFs imageFs = environment.getImageFs();
        Context context = environment.getContext();
        String box64Version = container.getBox64Version();
        File rootDir = imageFs.getRootDir();
        File box64File = new File(rootDir, "usr/bin/box64");
        if (box64File.exists() && box64Version.equals(container.getExtra("box64Version"))) {
            return;
        }

        Log.i("Extraction", "Extracting required box64 version: " + box64Version);
        ContentProfile profile = contentsManager.getProfileByEntryName("box64-" + box64Version);
        if (profile != null) {
            contentsManager.applyContent(profile);
        } else {
            TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, context.getAssets(), "box86_64/box64-" + box64Version + "-bionic.tzst", rootDir);
        }

        container.putExtra("box64Version", box64Version);
        container.saveData();

        if (box64File.exists()) {
            FileUtils.chmod(box64File, 0755);
        }
    }

    private void extractEmulatorsDlls() {
        Context context = environment.getContext();
        File rootDir = environment.getImageFs().getRootDir();
        File system32dir = new File(rootDir + "/home/xuser/.wine/drive_c/windows/system32");
        boolean containerDataChanged = false;

        String wowbox64Version = container.getBox64Version();
        String fexcoreVersion = container.getFEXCoreVersion();

        if (!wowbox64Version.equals(container.getExtra("appliedWowbox64Version"))) {
            ContentProfile wowboxprofile = contentsManager.getProfileByEntryName("wowbox64-" + wowbox64Version);
            if (wowboxprofile != null) {
                contentsManager.applyContent(wowboxprofile);
            } else {
                Log.d("Extraction", "Extracting box64Version: " + wowbox64Version);
                SharedComponents.extractAndLink(environment.getContext(), "wowbox64-" + wowbox64Version, TarCompressorUtils.Type.ZSTD, "wowbox64/wowbox64-" + wowbox64Version + ".tzst", system32dir, null);
            }
            container.putExtra("appliedWowbox64Version", wowbox64Version);
            containerDataChanged = true;
        }

        if (!fexcoreVersion.equals(container.getExtra("appliedFexcoreVersion"))) {
            ContentProfile fexprofile = contentsManager.getProfileByEntryName("fexcore-" + fexcoreVersion);
            if (fexprofile != null) {
                contentsManager.applyContent(fexprofile);
            } else {
                Log.d("Extraction", "Extracting fexcoreVersion: " + fexcoreVersion);
                SharedComponents.extractAndLink(environment.getContext(), "fexcore-" + fexcoreVersion, TarCompressorUtils.Type.ZSTD, "fexcore/fexcore-" + fexcoreVersion + ".tzst", system32dir, null);
            }
            container.putExtra("appliedFexcoreVersion", fexcoreVersion);
            containerDataChanged = true;
        }

        if (containerDataChanged) container.saveData();
    }

    private void addBox64EnvVars(EnvVars envVars, boolean enableLogs) {
        Context context = environment.getContext();
        ImageFs imageFs = ImageFs.find(context);
        envVars.put("BOX64_NOBANNER", ProcessHelper.PRINT_DEBUG && enableLogs ? "0" : "1");
        envVars.put("BOX64_DYNAREC", "1");

        if (enableLogs) {
            envVars.put("BOX64_LOG", "1");
            envVars.put("BOX64_DYNAREC_MISSING", "1");
        }

        envVars.putAll(Box86_64PresetManager.getEnvVars("box64", environment.getContext(), box64Preset));
        envVars.put("BOX64_X11GLX", "1");
        File box64RCFile = new File(imageFs.getRootDir(), "/etc/config.box64rc");
        envVars.put("BOX64_RCFILE", box64RCFile.getPath());
    }

    public String execShellCommand(String command) {
        return execShellCommand(command, true);
    }

    public String execShellCommand(String command, boolean includeStderr) {
        return execShellCommand(command, includeStderr, null, null);
    }

    public String execShellCommand(String command, boolean includeStderr, EnvVars extraEnv, String[] unsetEnv) {
        Context context = environment.getContext();
        ImageFs imageFs = ImageFs.find(context);
        File rootDir = imageFs.getRootDir();
        EnvVars envVars = new EnvVars();
        addBox64EnvVars(envVars, false);

        envVars.put("HOME", imageFs.home_path);
        envVars.put("USER", ImageFs.USER);
        envVars.put("TMPDIR", imageFs.getRootDir().getPath() + "/tmp");
        envVars.put("DISPLAY", ":0");

        String winePath = imageFs.getWinePath() + "/bin";

        Log.d("BionicProgramLauncherComponent", "WinePath is " + winePath);

        envVars.put("PATH", winePath + ":" + rootDir.getPath() + "/usr/bin");
        if (BuildConfig.MODERN_ANDROID) envVars.put("REDIRECT_EXEC__PROC_SELF_EXE", winePath + "/wine");

        String ldLibraryPath = rootDir.getPath() + "/usr/lib" + ":" + "/system/lib64";
        if (BuildConfig.MODERN_ANDROID) ldLibraryPath += ":" + imageFs.getWinePath() + "/lib";
        envVars.put("LD_LIBRARY_PATH", ldLibraryPath);
        envVars.put("ANDROID_SYSVSHM_SERVER", rootDir.getPath() + UnixSocketConfig.SYSVSHM_SERVER_PATH);
        envVars.put("WINE_NO_DUPLICATE_EXPLORER", "1");
        envVars.put("PREFIX", rootDir.getPath() + "/usr");
        envVars.put("WINE_DISABLE_FULLSCREEN_HACK", "1");
        envVars.put("SteamGameId", "0");

        String ld_preload = "";
        String sysvPath = imageFs.getLibDir() + "/libandroid-sysvshm.so";
        String replacePath = resolveLibredirectPreload(imageFs);

        if (new File(sysvPath).exists()) ld_preload += sysvPath;

        if (replacePath != null) ld_preload += ":" + replacePath;

        envVars.put("LD_PRELOAD", ld_preload);

        String emulator = container.getEmulator();
        if (this.envVars != null) envVars.putAll(this.envVars);
        try {
            ContainerOverlay.applyBionicLaunchEnv(context, container, envVars);
        }
        catch (IllegalStateException e) {
            Log.e("BionicProgramLauncherComponent", e.getMessage());
            return "";
        }
        if (extraEnv != null) envVars.putAll(extraEnv);
        if (unsetEnv != null) for (String name : unsetEnv) envVars.remove(name);

        String finalCommand = getFinalCommand(winePath, emulator, envVars, imageFs.getBinDir(), command);

        File box64File = new File(rootDir, "/usr/bin/box64");
        if (box64File.exists()) {
            FileUtils.chmod(box64File, 0755);
        }

        Log.d("BionicProgramLauncherComponent", "Shell command is " + finalCommand);
        return ProcessHelper.execWithOutput(finalCommand, envVars.toStringArray(),
                workingDir != null ? workingDir : imageFs.getRootDir(), includeStderr);
    }

    public void restartWineServer() {
        ProcessHelper.terminateAllWineProcesses();
        pid = execGuestProgram();
        Log.d("BionicProgramLauncherComponent", "Wine restarted successfully");

    }
}

package com.winlator.fexcore;

import android.content.Context;
import com.winlator.core.FileUtils;
import com.winlator.xenvironment.ImageFs;
import java.io.File;
import timber.log.Timber;

public final class FEXCoreManager {
    private static final String[] APP_CONFIG_EXE_NAMES = {};

    private static final String APP_CONFIG_CONTENT = "{\n" +
        "  \"Config\": {\n" +
        "    \"Multiblock\": \"0\",\n" +
        "    \"X87ReducedPrecision\": \"1\",\n" +
        "    \"VectorTSOEnabled\": \"1\",\n" +
        "    \"HalfBarrierTSOEnabled\": \"1\",\n" +
        "    \"MonoHacks\": \"0\"\n" +
        "  }\n" +
        "}\n";

    FEXCoreManager() {
    }

    public static File ensureAppConfigOverrides(Context context) {
        try {
            ImageFs imageFs = ImageFs.find(context);
            File rootDir = imageFs.getRootDir();
            File baseDir = new File(rootDir, "/home/xuser/.fex-emu");
            File appConfigDir = new File(baseDir, "AppConfig");

            for (String exeName : APP_CONFIG_EXE_NAMES) {
                File appConfigFile = new File(appConfigDir, exeName + ".json");
                FileUtils.writeString(appConfigFile, APP_CONFIG_CONTENT);
            }

            return baseDir;
        } catch (Exception e) {
            Timber.e(e, "Failed to write FEX app config overrides");
            return null;
        }
    }
}

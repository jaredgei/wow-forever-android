package com.winlator.fexcore;

import android.content.Context;
import app.gamenative.R;
import com.winlator.PrefManager;
import com.winlator.core.envvars.EnvVars;
import java.util.ArrayList;
import java.util.Iterator;

public abstract class FEXCorePresetManager {
    public static EnvVars getEnvVars(Context context, String id) {
        EnvVars envVars = new EnvVars();

        if (id.equals(FEXCorePreset.STABILITY)) {
            envVars.put("FEX_TSOENABLED", "1");
            envVars.put("FEX_VECTORTSOENABLED", "1");
            envVars.put("FEX_MEMCPYSETTSOENABLED", "1");
            envVars.put("FEX_HALFBARRIERTSOENABLED", "1");
            envVars.put("FEX_X87REDUCEDPRECISION", "0");
            envVars.put("FEX_MULTIBLOCK", "0");
        }
        else if (id.equals(FEXCorePreset.COMPATIBILITY)) {
            envVars.put("FEX_TSOENABLED", "1");
            envVars.put("FEX_VECTORTSOENABLED", "1");
            envVars.put("FEX_MEMCPYSETTSOENABLED", "1");
            envVars.put("FEX_HALFBARRIERTSOENABLED", "1");
            envVars.put("FEX_X87REDUCEDPRECISION", "0");
            envVars.put("FEX_MULTIBLOCK", "1");
        }
        else if (id.equals(FEXCorePreset.INTERMEDIATE)) {
            envVars.put("FEX_TSOENABLED", "1");
            envVars.put("FEX_VECTORTSOENABLED", "0");
            envVars.put("FEX_MEMCPYSETTSOENABLED", "0");
            envVars.put("FEX_HALFBARRIERTSOENABLED", "1");
            envVars.put("FEX_X87REDUCEDPRECISION", "1");
            envVars.put("FEX_MULTIBLOCK", "1");
        }
        else if (id.equals(FEXCorePreset.PERFORMANCE)) {
            envVars.put("FEX_TSOENABLED", "0");
            envVars.put("FEX_VECTORTSOENABLED", "0");
            envVars.put("FEX_MEMCPYSETTSOENABLED", "0");
            envVars.put("FEX_HALFBARRIERTSOENABLED", "0");
            envVars.put("FEX_X87REDUCEDPRECISION", "1");
            envVars.put("FEX_MULTIBLOCK", "1");
        }
        else if (id.equals(FEXCorePreset.EXTREME)) {
            envVars.put("FEX_TSOENABLED", "0");
            envVars.put("FEX_VECTORTSOENABLED", "0");
            envVars.put("FEX_MEMCPYSETTSOENABLED", "0");
            envVars.put("FEX_HALFBARRIERTSOENABLED", "0");
            envVars.put("FEX_X87REDUCEDPRECISION", "1");
            envVars.put("FEX_MULTIBLOCK", "1");

            // Based from PERFORMANCE
            envVars.put("FEX_SMALLTSCSCALE", "1");
            envVars.put("FEX_VOLATILEMETADATA", "1");
        }
        else if (id.equals(FEXCorePreset.DENUVO)) {
            envVars.put("FEX_TSOENABLED", "0");
            envVars.put("FEX_VECTORTSOENABLED", "0");
            envVars.put("FEX_MEMCPYSETTSOENABLED", "0");
            envVars.put("FEX_HALFBARRIERTSOENABLED", "0");
            envVars.put("FEX_X87REDUCEDPRECISION", "1");
            envVars.put("FEX_MULTIBLOCK", "1");

            // Based from EXTREME
            envVars.put("FEX_SMALLTSCSCALE", "1");
            envVars.put("FEX_VOLATILEMETADATA", "1");
            envVars.put("FEX_SMCCHECKS", "full");
            envVars.put("FEX_HIDEHYPERVISORBIT", "1");
        }
        else if (id.startsWith(FEXCorePreset.CUSTOM)) {
            for (String[] preset : customPresetsIterator(context)) {
                if (preset[0].equals(id)) {
                    envVars.putAll(preset[2]);
                    break;
                }
            }
        }

        return envVars;
    }

    public static ArrayList<FEXCorePreset> getPresets(Context context) {
        ArrayList<FEXCorePreset> presets = new ArrayList<>();
        presets.add(new FEXCorePreset(FEXCorePreset.STABILITY, context.getString(R.string.stability)));
        presets.add(new FEXCorePreset(FEXCorePreset.COMPATIBILITY, context.getString(R.string.compatibility)));
        presets.add(new FEXCorePreset(FEXCorePreset.INTERMEDIATE, context.getString(R.string.intermediate)));
        presets.add(new FEXCorePreset(FEXCorePreset.PERFORMANCE, context.getString(R.string.performance)));
        presets.add(new FEXCorePreset(FEXCorePreset.EXTREME, context.getString(R.string.extreme)));
        presets.add(new FEXCorePreset(FEXCorePreset.DENUVO, context.getString(R.string.denuvo)));
        for (String[] preset : customPresetsIterator(context)) presets.add(new FEXCorePreset(preset[0], preset[1]));
        return presets;
    }

    public static FEXCorePreset getPreset(Context context, String id) {
        for (FEXCorePreset preset : getPresets(context)) if (preset.id.equals(id)) return preset;
        return null;
    }

    private static Iterable<String[]> customPresetsIterator(Context context) {
        PrefManager.init(context);
        final String customPresetsStr = PrefManager.getString("fexcore_custom_presets", "");
        final String[] customPresets = customPresetsStr.split(",");
        final int[] index = {0};
        return () -> new Iterator<String[]>() {
            @Override
            public boolean hasNext() {
                return index[0] < customPresets.length && !customPresetsStr.isEmpty();
            }

            @Override
            public String[] next() {
                return customPresets[index[0]++].split("\\|");
            }
        };
    }
}

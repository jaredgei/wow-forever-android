package com.winlator.box86_64;

import android.content.Context;
import app.gamenative.R;
import com.winlator.PrefManager;
import com.winlator.core.envvars.EnvVars;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.Locale;

public abstract class Box86_64PresetManager {
    public static EnvVars getEnvVars(String prefix, Context context, String id) {
        String ucPrefix = prefix.toUpperCase(Locale.ENGLISH);
        EnvVars envVars = new EnvVars();

        if (id.equals(Box86_64Preset.STABILITY)) {
            envVars.put(ucPrefix + "_DYNAREC_SAFEFLAGS", "2");
            envVars.put(ucPrefix + "_DYNAREC_FASTNAN", "0");
            envVars.put(ucPrefix + "_DYNAREC_FASTROUND", "0");
            envVars.put(ucPrefix + "_DYNAREC_X87DOUBLE", "1");
            envVars.put(ucPrefix + "_DYNAREC_BIGBLOCK", "0");
            envVars.put(ucPrefix + "_DYNAREC_STRONGMEM", "2");
            envVars.put(ucPrefix + "_DYNAREC_FORWARD", "128");
            envVars.put(ucPrefix + "_DYNAREC_CALLRET", "0");
            envVars.put(ucPrefix + "_DYNAREC_WAIT", "0");
            if (ucPrefix.equals("BOX64")) {
                envVars.put("BOX64_AVX", "0");
                envVars.put("BOX64_UNITYPLAYER", "1");
                envVars.put("BOX64_MMAP32", "0");
            }
        } else if (id.equals(Box86_64Preset.COMPATIBILITY)) {
            envVars.put(ucPrefix + "_DYNAREC_SAFEFLAGS", "2");
            envVars.put(ucPrefix + "_DYNAREC_FASTNAN", "0");
            envVars.put(ucPrefix + "_DYNAREC_FASTROUND", "0");
            envVars.put(ucPrefix + "_DYNAREC_X87DOUBLE", "1");
            envVars.put(ucPrefix + "_DYNAREC_BIGBLOCK", "0");
            envVars.put(ucPrefix + "_DYNAREC_STRONGMEM", "1");
            envVars.put(ucPrefix + "_DYNAREC_FORWARD", "128");
            envVars.put(ucPrefix + "_DYNAREC_CALLRET", "0");
            envVars.put(ucPrefix + "_DYNAREC_WAIT", "1");
            if (ucPrefix.equals("BOX64")) {
                envVars.put("BOX64_AVX", "0");
                envVars.put("BOX64_UNITYPLAYER", "1");
                envVars.put("BOX64_MMAP32", "0");
            }
        } else if (id.equals(Box86_64Preset.INTERMEDIATE)) {
            envVars.put(ucPrefix + "_DYNAREC_SAFEFLAGS", "2");
            envVars.put(ucPrefix + "_DYNAREC_FASTNAN", "1");
            envVars.put(ucPrefix + "_DYNAREC_FASTROUND", "0");
            envVars.put(ucPrefix + "_DYNAREC_X87DOUBLE", "1");
            envVars.put(ucPrefix + "_DYNAREC_BIGBLOCK", "1");
            envVars.put(ucPrefix + "_DYNAREC_STRONGMEM", "0");
            envVars.put(ucPrefix + "_DYNAREC_FORWARD", "128");
            envVars.put(ucPrefix + "_DYNAREC_CALLRET", "0");
            envVars.put(ucPrefix + "_DYNAREC_WAIT", "1");
            if (ucPrefix.equals("BOX64")) {
                envVars.put("BOX64_AVX", "0");
                envVars.put("BOX64_UNITYPLAYER", "0");
                envVars.put("BOX64_MMAP32", "1");
            }
        } else if (id.equals(Box86_64Preset.PERFORMANCE)) {
            envVars.put(ucPrefix + "_DYNAREC_SAFEFLAGS", "1");
            envVars.put(ucPrefix + "_DYNAREC_FASTNAN", "1");
            envVars.put(ucPrefix + "_DYNAREC_FASTROUND", "1");
            envVars.put(ucPrefix + "_DYNAREC_X87DOUBLE", "0");
            envVars.put(ucPrefix + "_DYNAREC_BIGBLOCK", "3");
            envVars.put(ucPrefix + "_DYNAREC_STRONGMEM", "0");
            envVars.put(ucPrefix + "_DYNAREC_FORWARD", "512");
            envVars.put(ucPrefix + "_DYNAREC_CALLRET", "1");
            envVars.put(ucPrefix + "_DYNAREC_WAIT", "1");
            if (ucPrefix.equals("BOX64")) {
                envVars.put("BOX64_AVX", "0");
                envVars.put("BOX64_UNITYPLAYER", "0");
                envVars.put("BOX64_MMAP32", "1");
            }
        } else if (id.equals(Box86_64Preset.DENUVO)) {
            envVars.put(ucPrefix + "_DYNAREC_SAFEFLAGS", "2");
            envVars.put(ucPrefix + "_DYNAREC_FASTNAN", "0");
            envVars.put(ucPrefix + "_DYNAREC_FASTROUND", "0");
            envVars.put(ucPrefix + "_DYNAREC_X87DOUBLE", "1");
            envVars.put(ucPrefix + "_DYNAREC_BIGBLOCK", "0");
            envVars.put(ucPrefix + "_DYNAREC_STRONGMEM", "3");
            envVars.put(ucPrefix + "_DYNAREC_FORWARD", "512");
            envVars.put(ucPrefix + "_DYNAREC_CALLRET", "0");
            envVars.put(ucPrefix + "_DYNAREC_WAIT", "0");
            if (ucPrefix.equals("BOX64")) {
                envVars.put("BOX64_AVX", "0");
                envVars.put("BOX64_UNITYPLAYER", "1");
                envVars.put("BOX64_MMAP32", "0");
            }
        } else if (id.equals(Box86_64Preset.UNITY)) {
            envVars.put(ucPrefix + "_DYNAREC_SAFEFLAGS", "1");
            envVars.put(ucPrefix + "_DYNAREC_FASTNAN", "1");
            envVars.put(ucPrefix + "_DYNAREC_FASTROUND", "1");
            envVars.put(ucPrefix + "_DYNAREC_X87DOUBLE", "0");
            envVars.put(ucPrefix + "_DYNAREC_BIGBLOCK", "3");
            envVars.put(ucPrefix + "_DYNAREC_STRONGMEM", "1");
            envVars.put(ucPrefix + "_DYNAREC_FORWARD", "512");
            envVars.put(ucPrefix + "_DYNAREC_CALLRET", "1");
            envVars.put(ucPrefix + "_DYNAREC_WAIT", "0");
            if (ucPrefix.equals("BOX64")) {
                envVars.put("BOX64_AVX", "2");
                envVars.put("BOX64_UNITYPLAYER", "0");
                envVars.put("BOX64_MMAP32", "0");
            }
        } else if (id.equals(Box86_64Preset.UNITY_MONO_BLEEDING_EDGE)) {
            envVars.put(ucPrefix + "_DYNAREC_SAFEFLAGS", "1");
            envVars.put(ucPrefix + "_DYNAREC_FASTNAN", "1");
            envVars.put(ucPrefix + "_DYNAREC_FASTROUND", "1");
            envVars.put(ucPrefix + "_DYNAREC_X87DOUBLE", "0");
            envVars.put(ucPrefix + "_DYNAREC_BIGBLOCK", "0");
            envVars.put(ucPrefix + "_DYNAREC_STRONGMEM", "1");
            envVars.put(ucPrefix + "_DYNAREC_FORWARD", "512");
            envVars.put(ucPrefix + "_DYNAREC_CALLRET", "1");
            envVars.put(ucPrefix + "_DYNAREC_WAIT", "0");
            if (ucPrefix.equals("BOX64")) {
                envVars.put("BOX64_AVX", "0");
                envVars.put("BOX64_UNITYPLAYER", "0");
                envVars.put("BOX64_MMAP32", "0");
            }
        } else if (id.startsWith(Box86_64Preset.CUSTOM)) {
            for (String[] preset : customPresetsIterator(prefix, context)) {
                if (preset[0].equals(id)) {
                    envVars.putAll(preset[2]);
                    break;
                }
            }
        }

        return envVars;
    }

    public static ArrayList<Box86_64Preset> getPresets(String prefix, Context context) {
        ArrayList<Box86_64Preset> presets = new ArrayList<>();
        presets.add(new Box86_64Preset(Box86_64Preset.STABILITY, context.getString(R.string.stability)));
        presets.add(new Box86_64Preset(Box86_64Preset.COMPATIBILITY, context.getString(R.string.compatibility)));
        presets.add(new Box86_64Preset(Box86_64Preset.INTERMEDIATE, context.getString(R.string.intermediate)));
        presets.add(new Box86_64Preset(Box86_64Preset.PERFORMANCE, context.getString(R.string.performance)));
        presets.add(new Box86_64Preset(Box86_64Preset.UNITY, context.getString(R.string.unity)));
        presets.add(new Box86_64Preset(Box86_64Preset.UNITY_MONO_BLEEDING_EDGE, context.getString(R.string.unity_mono_bleeding_edge)));
        presets.add(new Box86_64Preset(Box86_64Preset.DENUVO, context.getString(R.string.denuvo)));
        for (String[] preset : customPresetsIterator(prefix, context))
            presets.add(new Box86_64Preset(preset[0], preset[1]));
        return presets;
    }

    public static Box86_64Preset getPreset(String prefix, Context context, String id) {
        for (Box86_64Preset preset : getPresets(prefix, context))
            if (preset.id.equals(id)) return preset;
        return null;
    }

    private static Iterable<String[]> customPresetsIterator(String prefix, Context context) {
        PrefManager.init(context);
        final String customPresetsStr = PrefManager.getString(prefix + "_custom_presets", "");
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

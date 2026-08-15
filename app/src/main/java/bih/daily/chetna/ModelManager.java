package bih.daily.chetna;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Keeps track of the on-device model files the user can pick from.
 *
 * <p>There is no remote catalog: a "model" is simply an absolute path to a {@code .litertlm} (or
 * {@code .bin}) file. Paths come from two places — ones the user explicitly imported (persisted in
 * {@link SharedPreferences}) and ones found by scanning the app's own model directories, which is
 * how downloads show up without any extra bookkeeping.
 */
public class ModelManager {
    private static final String PREFS_NAME = "ModelPrefs";
    private static final String KEY_MODELS = "added_models";
    private static final String KEY_SELECTED = "selected_model";

    private final Context context;
    private final SharedPreferences prefs;

    public ModelManager(Context context) {
        this.context = context;
        this.prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    public void addModelPath(String path) {
        Set<String> models = new HashSet<>(prefs.getStringSet(KEY_MODELS, new HashSet<>()));
        models.add(path);
        prefs.edit().putStringSet(KEY_MODELS, models).apply();
    }

    public List<String> getModelPaths() {
        Set<String> models = prefs.getStringSet(KEY_MODELS, new HashSet<>());
        List<String> result = new ArrayList<>(models);

        // Also scan app-specific models directory (Internal)
        File internalDir = new File(context.getFilesDir(), "models");
        scanDir(internalDir, result);

        // Also scan app-private external models directory (where DownloadManager saves)
        File externalDir = context.getExternalFilesDir("models");
        if (externalDir != null) {
            scanDir(externalDir, result);
        }

        return result;
    }

    private void scanDir(File dir, List<String> result) {
        if (!dir.exists()) {
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
        }
        File[] files = dir.listFiles();
        if (files != null) {
            for (File f : files) {
                if (f.getName().endsWith(".litertlm") || f.getName().endsWith(".bin")) {
                    if (!result.contains(f.getAbsolutePath())) {
                        result.add(f.getAbsolutePath());
                    }
                }
            }
        }
    }

    public String getSelectedModel() {
        return prefs.getString(KEY_SELECTED, null);
    }

    public void setSelectedModel(String path) {
        prefs.edit().putString(KEY_SELECTED, path).apply();
    }
}

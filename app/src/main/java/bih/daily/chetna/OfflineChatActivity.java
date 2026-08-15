package bih.daily.chetna;

import android.app.DownloadManager;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.util.Log;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.RadioButton;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.ai.edge.litertlm.Backend;
import com.google.ai.edge.litertlm.Content;
import com.google.ai.edge.litertlm.Contents;
import com.google.ai.edge.litertlm.Conversation;
import com.google.ai.edge.litertlm.ConversationConfig;
import com.google.ai.edge.litertlm.Engine;
import com.google.ai.edge.litertlm.EngineConfig;
import com.google.ai.edge.litertlm.Message;
import com.google.ai.edge.litertlm.MessageCallback;
import com.google.ai.edge.litertlm.SamplerConfig;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;

/**
 * Fully on-device chat. The model file lives on the phone, the LiteRT-LM engine runs it locally and
 * nothing the user types leaves the device — the only network call in the whole screen is the
 * optional model download.
 */
public class OfflineChatActivity extends AppCompatActivity {

    /** Starter prompts for the empty state — deliberately short and local-friendly. */
    private static final List<String> OFFLINE_STARTERS = Arrays.asList(
            "Explain quantum computing simply",
            "Write a short bedtime story",
            "Give me 5 dinner ideas",
            "Help me draft a polite reply",
            "Summarise the water cycle");

    private RecyclerView recyclerView;
    private ChatAdapter adapter;
    private final List<ChatMessage> messages = new ArrayList<>();
    private EditText inputField;
    private ProgressBar loadingProgress;
    private TextView statusText;
    private Engine engine;
    private Conversation currentConversation;

    private long lastUpdateTime = 0;
    private static final long UPDATE_THROTTLE_MS = 64; // Approx 15 FPS for UI updates

    // Track whether user has manually scrolled away from bottom during streaming
    private boolean userHasScrolledUp = false;
    private boolean isUserDragging = false;

    private ModelManager modelManager;
    private TextView currentModelText;
    private View emptyState;
    private LinearLayout suggestionChips;

    /** Held so it can be unregistered in {@link #onDestroy()} instead of leaking. */
    private BroadcastReceiver downloadReceiver;

    private final ActivityResultLauncher<Intent> modelPickerLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                    Uri uri = result.getData().getData();
                    if (uri != null) {
                        handlePickedModel(uri);
                    }
                }
            }
    );

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_offline_chat);

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.offline_chat_layout), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            Insets ime = insets.getInsets(WindowInsetsCompat.Type.ime());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, Math.max(systemBars.bottom, ime.bottom));
            return insets;
        });

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);

        recyclerView = findViewById(R.id.chat_recycler_view);
        inputField = findViewById(R.id.query_input);
        loadingProgress = findViewById(R.id.loading_progress);
        statusText = findViewById(R.id.status_text);
        emptyState = findViewById(R.id.empty_state);
        ImageButton sendButton = findViewById(R.id.btn_send);

        currentModelText = findViewById(R.id.current_model_text);
        suggestionChips = findViewById(R.id.suggestion_chips);
        Button btnManageModels = findViewById(R.id.btn_manage_models);

        modelManager = new ModelManager(this);

        btnManageModels.setOnClickListener(v -> openModelManager());

        ImageButton btnNewChat = findViewById(R.id.btn_new_chat);
        if (btnNewChat != null) {
            btnNewChat.setOnClickListener(v -> startNewConversation());
        }

        buildSuggestionChips();

        adapter = new ChatAdapter(messages);
        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        recyclerView.setAdapter(adapter);
        recyclerView.setItemAnimator(null); // Disable animations to prevent flickering during rapid updates

        // Track user scroll gestures to allow free scrolling during streaming
        recyclerView.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrollStateChanged(@NonNull RecyclerView rv, int newState) {
                if (newState == RecyclerView.SCROLL_STATE_DRAGGING) {
                    isUserDragging = true;
                } else if (newState == RecyclerView.SCROLL_STATE_IDLE) {
                    if (isUserDragging) {
                        // User finished a manual scroll — check if they're at the bottom
                        userHasScrolledUp = !isAtBottom();
                        isUserDragging = false;
                    }
                }
            }
        });

        sendButton.setOnClickListener(v -> sendMessage());

        initializeEngine();
        registerDownloadReceiver();
        updateEmptyState();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.menu_offline_chat, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        if (item.getItemId() == R.id.action_open_chetna_satra) {
            startActivity(new Intent(this, ChetnaSatraActivity.class));
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private boolean isAtBottom() {
        LinearLayoutManager lm = (LinearLayoutManager) recyclerView.getLayoutManager();
        if (lm == null || messages.isEmpty()) return true;
        int lastVisible = lm.findLastCompletelyVisibleItemPosition();
        return lastVisible >= messages.size() - 1;
    }

    private void registerDownloadReceiver() {
        IntentFilter filter = new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE);
        downloadReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                long downloadId = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1);
                if (downloadId != -1) {
                    Toast.makeText(OfflineChatActivity.this,
                            "Download complete! You can now select the model in Model Manager.",
                            Toast.LENGTH_LONG).show();
                }
            }
        };
        // ACTION_DOWNLOAD_COMPLETE is a system broadcast, so the receiver must be exported.
        ContextCompat.registerReceiver(this, downloadReceiver, filter, ContextCompat.RECEIVER_EXPORTED);
    }

    // ── Engine lifecycle ─────────────────────────────────────────────────────

    /**
     * Loads the selected model into a LiteRT-LM {@link Engine} and opens a {@link Conversation}.
     *
     * <p>GPU is tried first because it is several times faster; a lot of devices fail to bring up
     * the OpenCL delegate, so a CPU config is used as the fallback rather than failing outright.
     */
    private void initializeEngine() {
        updateModelUI();

        String modelPath = modelManager.getSelectedModel();
        if (modelPath == null) {
            statusText.setText(R.string.offline_select_model);
            statusText.setVisibility(View.VISIBLE);
            return;
        }

        loadingProgress.setVisibility(View.VISIBLE);
        statusText.setVisibility(View.VISIBLE);
        statusText.setText(R.string.offline_initializing);

        File modelFile = new File(modelPath);
        if (!modelFile.exists()) {
            loadingProgress.setVisibility(View.GONE);
            statusText.setText("Model file not found at: " + modelPath + ". Please re-add it.");
            Log.e("OfflineChat", "Model file not found at path: " + modelPath);
            return;
        }

        new Thread(() -> {
            try {
                if (currentConversation != null) {
                    try { currentConversation.close(); } catch (Exception ignored) {}
                    currentConversation = null;
                }
                if (engine != null) {
                    try { engine.close(); } catch (Exception ignored) {}
                    engine = null;
                }

                Log.i("OfflineChat", "Attempting GPU initialization with: " + modelFile.getAbsolutePath());
                EngineConfig config = new EngineConfig(
                        modelFile.getAbsolutePath(), new Backend.GPU(), null, null, null, null, null);
                try {
                    engine = new Engine(config);
                    engine.initialize();
                } catch (Exception gpuEx) {
                    Log.w("OfflineChat", "GPU init failed, falling back to CPU: " + gpuEx.getMessage());
                    EngineConfig cpuConfig = new EngineConfig(
                            modelFile.getAbsolutePath(), new Backend.CPU(null), null, null, null, null, null);
                    engine = new Engine(cpuConfig);
                    engine.initialize();
                }

                // Creating the conversation is cheap compared to engine init, but it still touches
                // native code, so keep it off the main thread.
                Conversation conversation = engine.createConversation(newConversationConfig());

                runOnUiThread(() -> {
                    currentConversation = conversation;
                    loadingProgress.setVisibility(View.GONE);
                    statusText.setVisibility(View.GONE);
                    addMessage(new ChatMessage("Engine ready. How can I help you offline?", false));
                    Log.i("OfflineChat", "Engine and Conversation successfully initialized");
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    loadingProgress.setVisibility(View.GONE);
                    statusText.setVisibility(View.VISIBLE);
                    statusText.setText("Error initializing engine: " + e.getMessage());
                    Log.e("OfflineChat", "Engine initialization failed", e);
                });
            }
        }).start();
    }

    /**
     * Sampling and conversation defaults. Prompt templating, stop tokens and the KV cache are all
     * handled inside the runtime and the {@code .litertlm} file, so nothing is formatted here.
     */
    private ConversationConfig newConversationConfig() {
        SamplerConfig samplerConfig = new SamplerConfig(/* topK= */ 40, /* topP= */ 0.95,
                /* temperature= */ 1.0, /* seed= */ 0);
        return new ConversationConfig(
                Contents.Companion.of(""),      // no system instruction
                Collections.emptyList(),        // initial messages
                Collections.emptyList(),        // tools
                samplerConfig,
                true,                           // automatic tool calling
                null,                           // channels
                Collections.emptyMap());        // extra context
    }

    // ── Suggestion chips ─────────────────────────────────────────────────────

    /**
     * Builds tappable starter prompts so a freshly opened screen isn't just a blank box.
     * The chips fill the empty-state container and vanish once the first message is sent.
     */
    private void buildSuggestionChips() {
        if (suggestionChips == null) return;
        suggestionChips.removeAllViews();
        float density = getResources().getDisplayMetrics().density;
        int hPad = (int) (16 * density);
        int vPad = (int) (8 * density);
        int margin = (int) (6 * density);
        for (String starter : OFFLINE_STARTERS) {
            TextView chip = new TextView(this);
            chip.setText(starter);
            chip.setTextSize(13f);
            chip.setTextColor(ContextCompat.getColor(this, R.color.text_primary));
            chip.setBackgroundResource(R.drawable.bg_suggestion_chip);
            chip.setPadding(hPad, vPad, hPad, vPad);
            chip.setClickable(true);
            chip.setFocusable(true);
            chip.setMaxLines(2);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.bottomMargin = margin;
            chip.setLayoutParams(lp);
            chip.setOnClickListener(v -> fillInputFromChip(starter));
            suggestionChips.addView(chip);
        }
    }

    /** Populates the input field from a chip tap and moves focus there, ready to send. */
    private void fillInputFromChip(String text) {
        inputField.setText(text);
        inputField.setSelection(text.length());
        inputField.requestFocus();
    }

    // ── New conversation ─────────────────────────────────────────────────────

    /**
     * Resets the conversation to a blank state. The engine and model stay loaded so the user
     * gets a fresh chat without the model-initialisation delay.
     */
    private void startNewConversation() {
        if (messages.isEmpty()) return; // Already blank — nothing to do.

        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.offline_new_chat)
                .setMessage("Start a new conversation? The current chat will be cleared.")
                .setPositiveButton("New chat", (d, w) -> clearChat())
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void clearChat() {
        messages.clear();
        adapter.notifyDataSetChanged();
        updateEmptyState();
        buildSuggestionChips();

        // Re-create the Conversation object so the model forgets prior context but the engine
        // stays warm — re-initialising the engine is the slow step (several seconds on GPU).
        if (engine != null) {
            try {
                if (currentConversation != null) {
                    try { currentConversation.close(); } catch (Exception ignored) {}
                }
                currentConversation = engine.createConversation(newConversationConfig());
            } catch (Exception e) {
                Log.e("OfflineChat", "Failed to reset conversation", e);
            }
        }
    }

    // ── Sending / streaming ──────────────────────────────────────────────────

    private void sendMessage() {
        String text = inputField.getText().toString().trim();
        if (text.isEmpty()) return;

        if (engine == null || currentConversation == null) {
            Toast.makeText(this, "Engine is initializing or not selected. Please wait.", Toast.LENGTH_SHORT).show();
            return;
        }

        // Reset scroll tracking for new conversation turn
        userHasScrolledUp = false;

        addMessage(new ChatMessage(text, true));
        inputField.setText("");

        // Create a placeholder for the model response
        ChatMessage modelResponse = new ChatMessage("", false);
        addMessage(modelResponse);

        sendMessageToInternalEngine(text, modelResponse);
    }

    /**
     * Streams a reply into {@code modelResponse}. The callback delivers incremental deltas, so the
     * placeholder bubble is mutated in place and the adapter is only nudged every
     * {@link #UPDATE_THROTTLE_MS} to keep the list from thrashing.
     */
    private void sendMessageToInternalEngine(String text, ChatMessage modelResponse) {
        currentConversation.sendMessageAsync(text, new MessageCallback() {
            @Override
            public void onMessage(@NonNull Message message) {
                runOnUiThread(() -> {
                    String partialText = "";
                    if (!message.getContents().getContents().isEmpty()) {
                        Content content = message.getContents().getContents().get(0);
                        if (content instanceof Content.Text) {
                            partialText = ((Content.Text) content).getText();
                        }
                    }
                    modelResponse.text += partialText;

                    long currentTime = System.currentTimeMillis();
                    if (currentTime - lastUpdateTime > UPDATE_THROTTLE_MS) {
                        updateChatUI(false);
                        lastUpdateTime = currentTime;
                    }
                });
            }

            @Override
            public void onDone() {
                runOnUiThread(() -> updateChatUI(true));
            }

            @Override
            public void onError(@NonNull Throwable throwable) {
                runOnUiThread(() -> {
                    modelResponse.text = "Error: " + throwable.getMessage();
                    updateChatUI(true);
                });
            }
        }, new HashMap<>());
    }

    private void updateChatUI(boolean forceScroll) {
        adapter.notifyItemChanged(messages.size() - 1);

        // If streaming is done, reset the scroll-lock flag
        if (forceScroll) {
            userHasScrolledUp = false;
        }

        // Don't auto-scroll if user has manually scrolled up to read earlier content
        if (userHasScrolledUp) {
            return;
        }

        // Post scroll after layout pass so it doesn't fight with notifyItemChanged
        recyclerView.post(() -> recyclerView.scrollToPosition(messages.size() - 1));
    }

    private void addMessage(ChatMessage message) {
        messages.add(message);
        adapter.notifyItemInserted(messages.size() - 1);
        recyclerView.scrollToPosition(messages.size() - 1);
        updateEmptyState();
    }

    /** Shows the onboarding empty state only while there are no messages yet. */
    private void updateEmptyState() {
        if (emptyState != null) {
            emptyState.setVisibility(messages.isEmpty() ? View.VISIBLE : View.GONE);
        }
    }

    private void updateModelUI() {
        String path = modelManager.getSelectedModel();
        if (path != null) {
            File f = new File(path);
            currentModelText.setText(getString(R.string.offline_model_prefix, f.getName()));
        } else {
            currentModelText.setText(R.string.offline_model_none);
        }
    }

    // ── Model manager ────────────────────────────────────────────────────────

    private interface OnModelSelectedListener { void onSelected(String path); }

    private void openModelManager() {
        BottomSheetDialog dialog = new BottomSheetDialog(this);
        View view = getLayoutInflater().inflate(R.layout.bottom_sheet_model_manager, null);

        RecyclerView rv = view.findViewById(R.id.model_list_recycler);
        rv.setLayoutManager(new LinearLayoutManager(this));

        List<String> paths = modelManager.getModelPaths();
        ModelListAdapter modelAdapter = new ModelListAdapter(paths, modelManager.getSelectedModel(), path -> {
            modelManager.setSelectedModel(path);
            updateModelUI();

            initializeEngine();
            dialog.dismiss();
        });
        rv.setAdapter(modelAdapter);

        view.findViewById(R.id.btn_add_from_device).setOnClickListener(v -> {
            Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
            intent.setType("*/*");
            modelPickerLauncher.launch(intent);
            dialog.dismiss();
        });

        view.findViewById(R.id.btn_download_menu).setOnClickListener(v -> {
            showDownloadDialog();
            dialog.dismiss();
        });

        dialog.setContentView(view);
        dialog.show();
    }

    private void showDownloadDialog() {
        String[] options = {"Gemma-4-E2B (~2.4GB)"};
        new MaterialAlertDialogBuilder(this)
                .setTitle("Select Model to Download")
                .setItems(options, (dialog, which) -> {
                    if (which == 0) {
                        downloadModel("https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/main/gemma-4-E2B-it.litertlm",
                                "gemma-4.litertlm");
                    }
                })
                .show();
    }

    private void downloadModel(String url, String name) {
        DownloadManager.Request request = new DownloadManager.Request(Uri.parse(url));
        request.setTitle("Downloading AI Model");
        request.setDestinationInExternalFilesDir(this, "models", name);
        request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);

        DownloadManager manager = (DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE);
        if (manager == null) {
            Toast.makeText(this, "Downloads are unavailable on this device.", Toast.LENGTH_LONG).show();
            return;
        }

        long downloadId = manager.enqueue(request);
        Toast.makeText(this, "Download started", Toast.LENGTH_SHORT).show();

        // Surface live progress in-app instead of relying solely on the system notification,
        // so the user can see the multi-gigabyte model actually making progress.
        loadingProgress.setVisibility(View.VISIBLE);
        statusText.setVisibility(View.VISIBLE);
        statusText.setText("Starting download…");
        trackDownloadProgress(manager, downloadId);
    }

    private void trackDownloadProgress(DownloadManager manager, long downloadId) {
        new Thread(() -> {
            boolean finished = false;
            while (!finished) {
                DownloadManager.Query query = new DownloadManager.Query().setFilterById(downloadId);
                int status = -1;
                long downloaded = 0;
                long total = 0;
                try (Cursor cursor = manager.query(query)) {
                    if (cursor != null && cursor.moveToFirst()) {
                        int statusIdx = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS);
                        int soFarIdx = cursor.getColumnIndex(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR);
                        int totalIdx = cursor.getColumnIndex(DownloadManager.COLUMN_TOTAL_SIZE_BYTES);
                        if (statusIdx >= 0) status = cursor.getInt(statusIdx);
                        if (soFarIdx >= 0) downloaded = cursor.getLong(soFarIdx);
                        if (totalIdx >= 0) total = cursor.getLong(totalIdx);
                    } else {
                        // Row gone (e.g., download removed) — stop polling.
                        finished = true;
                    }
                } catch (Exception e) {
                    finished = true;
                }

                final int fStatus = status;
                final long fDownloaded = downloaded;
                final long fTotal = total;

                if (status == DownloadManager.STATUS_SUCCESSFUL
                        || status == DownloadManager.STATUS_FAILED) {
                    finished = true;
                }

                final boolean isFinished = finished;
                runOnUiThread(() -> {
                    if (fStatus == DownloadManager.STATUS_FAILED) {
                        loadingProgress.setVisibility(View.GONE);
                        statusText.setText("Download failed. Please check your connection and try again.");
                    } else if (fStatus == DownloadManager.STATUS_SUCCESSFUL) {
                        loadingProgress.setVisibility(View.GONE);
                        statusText.setText("Download complete. Open Model Manager to select it.");
                    } else if (!isFinished) {
                        if (fTotal > 0) {
                            int percent = (int) ((fDownloaded * 100L) / fTotal);
                            statusText.setText("Downloading model… " + percent + "%");
                        } else {
                            statusText.setText("Downloading model…");
                        }
                    }
                });

                if (!finished) {
                    try {
                        Thread.sleep(700);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        finished = true;
                    }
                }
            }
        }, "model-download-progress").start();
    }

    /**
     * Copies a picked document into the app's models directory.
     *
     * <p>A document picked through the Storage Access Framework comes back as a {@code content://}
     * URI whose {@code getPath()} is not a real filesystem path, so the native engine could never
     * open it directly. Copying gives us a stable path the engine can memory-map.
     */
    private void handlePickedModel(Uri uri) {
        final String displayName = queryDisplayName(uri);

        loadingProgress.setVisibility(View.VISIBLE);
        statusText.setVisibility(View.VISIBLE);
        statusText.setText("Importing " + displayName + "…");

        new Thread(() -> {
            File modelsDir = new File(getExternalFilesDir(null), "models");
            if (!modelsDir.exists() && !modelsDir.mkdirs()) {
                runOnUiThread(() -> {
                    loadingProgress.setVisibility(View.GONE);
                    statusText.setText("Could not create the models folder. Please try again.");
                });
                return;
            }

            File destFile = new File(modelsDir, displayName);
            try (InputStream in = getContentResolver().openInputStream(uri);
                 OutputStream out = new FileOutputStream(destFile)) {
                if (in == null) {
                    throw new IOException("Unable to open the selected file.");
                }
                byte[] buffer = new byte[8192];
                int read;
                while ((read = in.read(buffer)) != -1) {
                    out.write(buffer, 0, read);
                }
                out.flush();

                final String path = destFile.getAbsolutePath();
                runOnUiThread(() -> {
                    modelManager.addModelPath(path);
                    modelManager.setSelectedModel(path);
                    updateModelUI();
                    initializeEngine();
                });
            } catch (Exception e) {
                Log.e("OfflineChat", "Failed to import model", e);
                // Don't leave a half-written file behind.
                if (destFile.exists()) {
                    //noinspection ResultOfMethodCallIgnored
                    destFile.delete();
                }
                runOnUiThread(() -> {
                    loadingProgress.setVisibility(View.GONE);
                    statusText.setVisibility(View.VISIBLE);
                    statusText.setText("Could not import the model: " + e.getMessage());
                    Toast.makeText(OfflineChatActivity.this,
                            "Import failed. Please pick a valid .litertlm model file.",
                            Toast.LENGTH_LONG).show();
                });
            }
        }).start();
    }

    /** Resolves a human-readable file name for a content URI, with a sensible fallback. */
    private String queryDisplayName(Uri uri) {
        String name = null;
        try (Cursor cursor = getContentResolver().query(uri, null, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (index >= 0) {
                    name = cursor.getString(index);
                }
            }
        } catch (Exception ignored) {
        }
        if (name == null || name.trim().isEmpty()) {
            String lastSegment = uri.getLastPathSegment();
            name = (lastSegment != null && !lastSegment.trim().isEmpty())
                    ? lastSegment.replaceAll("[\\\\/:]", "_")
                    : "model_" + System.currentTimeMillis() + ".litertlm";
        }
        return name;
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (downloadReceiver != null) {
            try { unregisterReceiver(downloadReceiver); } catch (Exception ignored) {}
            downloadReceiver = null;
        }
        if (currentConversation != null) {
            currentConversation.close();
        }
        if (engine != null) {
            engine.close();
        }
    }

    // --- Adapter for Model List ---
    private static class ModelListAdapter extends RecyclerView.Adapter<ModelListAdapter.ViewHolder> {
        private final List<String> paths;
        private final String selectedPath;
        private final OnModelSelectedListener listener;

        ModelListAdapter(List<String> paths, String selected, OnModelSelectedListener l) {
            this.paths = paths;
            this.selectedPath = selected;
            this.listener = l;
        }

        @NonNull
        @Override
        public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new ViewHolder(LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_model, parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
            String path = paths.get(position);
            File f = new File(path);
            holder.name.setText(f.getName());
            holder.path.setText(path);
            holder.radio.setChecked(path.equals(selectedPath));
            holder.itemView.setOnClickListener(v -> listener.onSelected(path));
        }

        @Override
        public int getItemCount() { return paths.size(); }

        static class ViewHolder extends RecyclerView.ViewHolder {
            TextView name, path;
            RadioButton radio;

            ViewHolder(View v) {
                super(v);
                name = v.findViewById(R.id.model_name);
                path = v.findViewById(R.id.model_path);
                radio = v.findViewById(R.id.model_radio);
            }
        }
    }

    // --- Inner Classes for Chat UI ---

    /** Mutable {@code text} is what lets a streaming reply grow inside one bubble. */
    private static class ChatMessage {
        String text;
        final boolean isUser;

        ChatMessage(String text, boolean isUser) {
            this.text = text;
            this.isUser = isUser;
        }
    }

    private static class ChatAdapter extends RecyclerView.Adapter<ChatAdapter.ViewHolder> {
        private final List<ChatMessage> data;

        ChatAdapter(List<ChatMessage> data) { this.data = data; }

        @NonNull
        @Override
        public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            Context ctx = parent.getContext();
            float density = ctx.getResources().getDisplayMetrics().density;
            int screenWidth = ctx.getResources().getDisplayMetrics().widthPixels;

            LinearLayout row = new LinearLayout(ctx);
            row.setLayoutParams(new RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT));
            int vPad = (int) (4 * density);
            row.setPadding(0, vPad, 0, vPad);

            TextView bubble = new TextView(ctx);
            bubble.setLayoutParams(new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT));
            bubble.setMaxWidth((int) (screenWidth * 0.8f));
            int hPad = (int) (16 * density);
            int vBubblePad = (int) (10 * density);
            bubble.setPadding(hPad, vBubblePad, hPad, vBubblePad);
            bubble.setTextSize(16);
            bubble.setLineSpacing(0f, 1.1f);
            // Long-press copies the message text — helpful for AI responses that users want to
            // paste into a document or share.
            bubble.setLongClickable(true);
            row.addView(bubble);

            return new ViewHolder(row, bubble);
        }

        @Override
        public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
            ChatMessage msg = data.get(position);
            TextView bubble = holder.bubble;
            LinearLayout row = (LinearLayout) holder.itemView;

            bubble.setText(msg.text);
            if (msg.isUser) {
                bubble.setBackgroundResource(R.drawable.bubble_user);
                bubble.setTextColor(ContextCompat.getColor(bubble.getContext(), R.color.chat_bubble_user_text));
                row.setGravity(Gravity.END);
            } else {
                bubble.setBackgroundResource(R.drawable.bubble_ai);
                bubble.setTextColor(ContextCompat.getColor(bubble.getContext(), R.color.chat_bubble_ai_text));
                row.setGravity(Gravity.START);
            }

            bubble.setOnLongClickListener(v -> {
                ClipboardManager cb = (ClipboardManager) v.getContext()
                        .getSystemService(Context.CLIPBOARD_SERVICE);
                if (cb == null) return false;
                cb.setPrimaryClip(ClipData.newPlainText("message", msg.text));
                Toast.makeText(v.getContext(), "Copied", Toast.LENGTH_SHORT).show();
                return true;
            });
        }

        @Override
        public int getItemCount() { return data.size(); }

        static class ViewHolder extends RecyclerView.ViewHolder {
            final TextView bubble;

            ViewHolder(View itemView, TextView bubble) {
                super(itemView);
                this.bubble = bubble;
            }
        }
    }
}

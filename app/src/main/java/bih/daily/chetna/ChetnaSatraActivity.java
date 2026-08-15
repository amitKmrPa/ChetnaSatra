package bih.daily.chetna;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.format.DateFormat;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.DatePicker;
import android.widget.NumberPicker;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.button.MaterialButtonToggleGroup;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import bih.daily.chetna.content.ChetnaSatraContent.Document;
import bih.daily.chetna.content.ChetnaSatraContent.Edition;
import bih.daily.chetna.content.ChetnaSatraGenerator;

/**
 * Shows a चेतना सत्र resource sheet and rebuilds it once a day.
 *
 * <p>The schedule is an <em>anchor instant</em> plus a fixed one-day period, not a countdown from
 * whenever the screen was opened. The user picks the exact moment the sheet should turn over — date,
 * hour, minute and second — and every following day it turns over at that same second.
 *
 * <p>Because a day is far longer than a screen session, the anchor is persisted and the schedule is
 * caught up on {@code onStart}: if the moment passed while the app was closed, a new sheet is built
 * straight away and the anchor rolls forward past however many days were missed. The one-second
 * ticker only drives the live countdown and the turn-over while the screen is actually up.
 */
public class ChetnaSatraActivity extends AppCompatActivity {

    private static final String PREFS = "ChetnaSatraPrefs";
    private static final String KEY_REVISION = "revision";
    private static final String KEY_EDITION = "edition";
    private static final String KEY_AUTO = "auto_refresh";
    private static final String KEY_NEXT_AT = "next_refresh_at";

    /** The sheet is a daily publication, so the period is fixed at one day. */
    private static final long REFRESH_PERIOD_MS = TimeUnit.DAYS.toMillis(1);
    private static final long TICK_MS = TimeUnit.SECONDS.toMillis(1);

    /** Used the first time round: tomorrow-or-today at 06:00:00, before the school day starts. */
    private static final int DEFAULT_HOUR = 6;

    private final Map<Edition, ChetnaSatraGenerator> generators = new EnumMap<>(Edition.class);
    private final Map<Edition, Document> latest = new EnumMap<>(Edition.class);

    private final Handler handler = new Handler(Looper.getMainLooper());

    private ChetnaSatraAdapter adapter;
    private RecyclerView recyclerView;
    private TextView editionText;
    private TextView stampText;
    private TextView countdownText;
    private TextView scheduleText;
    private MaterialButtonToggleGroup editionToggle;

    private SharedPreferences prefs;
    private Edition edition = Edition.PRIMARY;
    private boolean autoRefresh = true;
    /** Epoch millis of the next scheduled turn-over. */
    private long nextRefreshAt;

    /** Drives the live countdown and fires the turn-over the second it comes due. */
    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            if (System.currentTimeMillis() >= nextRefreshAt) {
                generateNow();
                advanceSchedule();
            }
            updateCountdown();
            handler.postDelayed(this, TICK_MS);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_chetna_satra);

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.chetna_satra_layout), (v, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return insets;
        });

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }
        toolbar.setNavigationOnClickListener(v -> finish());

        editionText = findViewById(R.id.cs_edition);
        stampText = findViewById(R.id.cs_stamp);
        countdownText = findViewById(R.id.cs_countdown);
        scheduleText = findViewById(R.id.cs_schedule);
        editionToggle = findViewById(R.id.cs_edition_toggle);
        recyclerView = findViewById(R.id.cs_recycler);

        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        autoRefresh = prefs.getBoolean(KEY_AUTO, true);
        edition = Edition.valueOf(prefs.getString(KEY_EDITION, Edition.PRIMARY.name()));
        nextRefreshAt = prefs.getLong(KEY_NEXT_AT, 0L);
        if (nextRefreshAt <= 0L) {
            nextRefreshAt = defaultAnchor();
            persistSchedule();
        }

        adapter = new ChetnaSatraAdapter();
        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        recyclerView.setAdapter(adapter);

        scheduleText.setOnClickListener(v -> showSchedulePicker());

        editionToggle.check(edition == Edition.PRIMARY
                ? R.id.cs_edition_primary : R.id.cs_edition_secondary);
        editionToggle.addOnButtonCheckedListener((group, checkedId, isChecked) -> {
            if (!isChecked) return;
            Edition selected = checkedId == R.id.cs_edition_primary
                    ? Edition.PRIMARY : Edition.SECONDARY;
            if (selected == edition) return;
            edition = selected;
            prefs.edit().putString(KEY_EDITION, edition.name()).apply();
            // Show whatever this edition last produced, building it the first time round.
            // Switching editions must not disturb the daily schedule.
            Document existing = latest.get(edition);
            if (existing == null) {
                generateNow();
            } else {
                render(existing);
            }
        });

        // When the scheduled moment has already passed, onStart's catch-up does the honours;
        // generating here as well would burn a rotation step for nothing.
        if (System.currentTimeMillis() < nextRefreshAt) {
            generateNow();
        }
        updateScheduleLabel();
    }

    @Override
    protected void onStart() {
        super.onStart();
        // Catch up on anything missed while the screen was away.
        if (System.currentTimeMillis() >= nextRefreshAt) {
            generateNow();
            advanceSchedule();
        }
        startTicking();
    }

    @Override
    protected void onStop() {
        super.onStop();
        handler.removeCallbacks(ticker);
    }

    // ── Generation ───────────────────────────────────────────────────────────

    private ChetnaSatraGenerator generatorFor(Edition target) {
        ChetnaSatraGenerator generator = generators.get(target);
        if (generator == null) {
            generator = new ChetnaSatraGenerator(target, prefs.getInt(KEY_REVISION, 0));
            generators.put(target, generator);
        }
        return generator;
    }

    /** Builds the next sheet for the current edition and puts it on screen. Schedule untouched. */
    private void generateNow() {
        ChetnaSatraGenerator generator = generatorFor(edition);
        Document document = generator.next();
        latest.put(edition, document);
        prefs.edit().putInt(KEY_REVISION, document.revision).apply();
        render(document);
    }

    private void render(Document document) {
        editionText.setText(document.edition.label);
        stampText.setText(getString(R.string.cs_stamp,
                document.dateLabel,
                document.revision,
                DateFormat.getTimeFormat(this).format(document.generatedAt)));
        adapter.submit(document);
        recyclerView.scrollToPosition(0);
    }

    // ── Schedule ─────────────────────────────────────────────────────────────

    /** Next 06:00:00 — today if it is still ahead, otherwise tomorrow. */
    private long defaultAnchor() {
        Calendar c = Calendar.getInstance();
        c.set(Calendar.HOUR_OF_DAY, DEFAULT_HOUR);
        c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        if (c.getTimeInMillis() <= System.currentTimeMillis()) {
            c.add(Calendar.DAY_OF_MONTH, 1);
        }
        return c.getTimeInMillis();
    }

    /**
     * Moves the anchor forward in whole days until it is in the future, so a device that was off
     * for a week fires once on return rather than once per missed day.
     */
    private void advanceSchedule() {
        long now = System.currentTimeMillis();
        if (nextRefreshAt <= 0L) nextRefreshAt = now;
        while (nextRefreshAt <= now) {
            nextRefreshAt += REFRESH_PERIOD_MS;
        }
        persistSchedule();
        updateScheduleLabel();
    }

    private void persistSchedule() {
        prefs.edit().putLong(KEY_NEXT_AT, nextRefreshAt).apply();
    }

    private void updateScheduleLabel() {
        scheduleText.setText(getString(R.string.cs_schedule_label, formatInstant(nextRefreshAt)));
    }

    private String formatInstant(long millis) {
        return new SimpleDateFormat("dd.MM.yyyy, HH:mm:ss", Locale.ENGLISH).format(millis);
    }

    private void startTicking() {
        handler.removeCallbacks(ticker);
        if (autoRefresh) {
            handler.post(ticker);
        } else {
            countdownText.setText(R.string.cs_auto_paused);
        }
    }

    /** Countdown down to the second, with a day count once there is more than 24 hours to go. */
    private void updateCountdown() {
        long remaining = Math.max(0L, nextRefreshAt - System.currentTimeMillis());
        long totalSeconds = remaining / 1000L;
        long days = totalSeconds / 86400L;
        long rest = totalSeconds % 86400L;
        String clock = String.format(Locale.ENGLISH, "%02d:%02d:%02d",
                rest / 3600L, (rest % 3600L) / 60L, rest % 60L);
        String value = days > 0 ? getString(R.string.cs_days_prefix, days, clock) : clock;
        countdownText.setText(getString(R.string.cs_next_in, value));
    }

    /**
     * Date plus hour/minute/second picker.
     *
     * <p>{@code TimePicker} has no seconds field, so the clock is three {@link NumberPicker}s. The
     * chosen instant is stored verbatim: if the user picks a moment that has already passed, the
     * ticker treats it as due and rolls the anchor forward from there.
     */
    private void showSchedulePicker() {
        View view = LayoutInflater.from(this).inflate(R.layout.dialog_refresh_time, null);
        DatePicker datePicker = view.findViewById(R.id.cs_pick_date);
        NumberPicker hourPicker = view.findViewById(R.id.cs_pick_hour);
        NumberPicker minutePicker = view.findViewById(R.id.cs_pick_minute);
        NumberPicker secondPicker = view.findViewById(R.id.cs_pick_second);

        Calendar current = Calendar.getInstance();
        current.setTimeInMillis(nextRefreshAt > 0L ? nextRefreshAt : System.currentTimeMillis());

        datePicker.updateDate(current.get(Calendar.YEAR), current.get(Calendar.MONTH),
                current.get(Calendar.DAY_OF_MONTH));
        setUpClockPicker(hourPicker, 23, current.get(Calendar.HOUR_OF_DAY));
        setUpClockPicker(minutePicker, 59, current.get(Calendar.MINUTE));
        setUpClockPicker(secondPicker, 59, current.get(Calendar.SECOND));

        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.cs_schedule_dialog_title)
                .setView(view)
                .setPositiveButton(R.string.cs_schedule_set, (dialog, which) -> {
                    Calendar chosen = Calendar.getInstance();
                    chosen.set(datePicker.getYear(), datePicker.getMonth(), datePicker.getDayOfMonth(),
                            hourPicker.getValue(), minutePicker.getValue(), secondPicker.getValue());
                    chosen.set(Calendar.MILLISECOND, 0);
                    nextRefreshAt = chosen.getTimeInMillis();
                    persistSchedule();
                    updateScheduleLabel();
                    updateCountdown();
                    // A schedule is pointless while auto-refresh is off, so turn it back on.
                    if (!autoRefresh) {
                        autoRefresh = true;
                        prefs.edit().putBoolean(KEY_AUTO, true).apply();
                        invalidateOptionsMenu();
                    }
                    startTicking();
                    Toast.makeText(this,
                            getString(R.string.cs_schedule_saved, formatInstant(nextRefreshAt)),
                            Toast.LENGTH_LONG).show();
                })
                .setNegativeButton(R.string.cs_cancel, null)
                .show();
    }

    private void setUpClockPicker(NumberPicker picker, int max, int value) {
        picker.setMinValue(0);
        picker.setMaxValue(max);
        picker.setValue(value);
        picker.setWrapSelectorWheel(true);
        picker.setFormatter(v -> String.format(Locale.ENGLISH, "%02d", v));
    }

    // ── Menu ─────────────────────────────────────────────────────────────────

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.menu_chetna_satra, menu);
        return true;
    }

    @Override
    public boolean onPrepareOptionsMenu(Menu menu) {
        MenuItem toggle = menu.findItem(R.id.action_toggle_auto);
        toggle.setTitle(autoRefresh ? R.string.cs_pause_auto : R.string.cs_resume_auto);
        return super.onPrepareOptionsMenu(menu);
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.action_refresh_now) {
            // A manual rebuild is a one-off; the daily anchor stays where the user put it.
            generateNow();
            return true;
        }
        if (id == R.id.action_set_schedule) {
            showSchedulePicker();
            return true;
        }
        if (id == R.id.action_toggle_auto) {
            autoRefresh = !autoRefresh;
            prefs.edit().putBoolean(KEY_AUTO, autoRefresh).apply();
            invalidateOptionsMenu();
            startTicking();
            return true;
        }
        if (id == R.id.action_share_sheet) {
            shareCurrentSheet();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void shareCurrentSheet() {
        Document document = latest.get(edition);
        if (document == null) return;
        Intent send = new Intent(Intent.ACTION_SEND);
        send.setType("text/plain");
        send.putExtra(Intent.EXTRA_SUBJECT,
                ChetnaSatraGenerator.HEADER_TITLE + " \u2014 " + document.dateLabel);
        send.putExtra(Intent.EXTRA_TEXT, document.toPlainText());
        startActivity(Intent.createChooser(send, getString(R.string.cs_share)));
    }
}

package bih.daily.chetna.content;

import bih.daily.chetna.content.ChetnaSatraContent.Block;
import bih.daily.chetna.content.ChetnaSatraContent.Document;
import bih.daily.chetna.content.ChetnaSatraContent.Edition;
import bih.daily.chetna.content.ChetnaSatraContent.Line;
import bih.daily.chetna.content.ChetnaSatraContent.Pair;
import bih.daily.chetna.content.ChetnaSatraContent.Para;
import bih.daily.chetna.content.ChetnaSatraContent.Qa;
import bih.daily.chetna.content.ChetnaSatraContent.Quote;
import bih.daily.chetna.content.ChetnaSatraContent.Section;
import bih.daily.chetna.content.ChetnaSatraContent.SubHeading;
import bih.daily.chetna.content.ContentBank.Confusable;
import bih.daily.chetna.content.ContentBank.Duo;
import bih.daily.chetna.content.ContentBank.SamasGroup;
import bih.daily.chetna.content.DayBank.DayEntry;
import bih.daily.chetna.content.DayBank.Story;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Random;

/**
 * Produces a fresh चेतना सत्र sheet on demand, guaranteeing that consecutive sheets differ.
 *
 * <p>Two rules drive the design:
 *
 * <ul>
 *   <li><b>The format never moves.</b> The section list, their order and their titles are fixed per
 *       edition. Only the blocks inside a section are re-drawn.
 *   <li><b>Part of the sheet changes, not all of it.</b> Each call refreshes a rotating slice of the
 *       refreshable sections and leaves the rest standing, which is how a real daily sheet behaves —
 *       प्रार्थना and the closing songs never change at all.
 * </ul>
 *
 * <p>Difference is guaranteed at two levels: every pool is dealt through a {@link Picker} that
 * cannot hand out the same entry twice in a row, and the finished document's
 * {@link Document#signature()} is compared against the previous one, forcing another pass in the
 * unlikely event they still match.
 */
public final class ChetnaSatraGenerator {

    /** How the sheet identifies itself, kept identical to the printed material. */
    public static final String HEADER_AUTHORITY = "राज्य शिक्षा शोध एवं प्रशिक्षण परिषद, बिहार, पटना";
    public static final String HEADER_TITLE = "चेतना सत्र संसाधन सामग्री";

    /** Builds one section from the pools. */
    private interface SectionBuilder {
        Section build();
    }

    private static final class Spec {
        final String key;
        final String title;
        final SectionBuilder builder;
        /** Pinned sections are part of the ritual and are never rotated out. */
        final boolean pinned;

        Spec(String key, String title, boolean pinned, SectionBuilder builder) {
            this.key = key;
            this.title = title;
            this.pinned = pinned;
            this.builder = builder;
        }
    }

    private final Edition edition;
    private final Random random;
    private final SeriesGenerator series;

    private final List<Spec> specs = new ArrayList<>();
    private final List<Integer> refreshable = new ArrayList<>();
    private final LinkedHashMap<String, Section> current = new LinkedHashMap<>();

    private int rotationCursor;
    private int revision;
    private String lastSignature;

    // Pools
    private final Picker<Duo> gk15;
    private final Picker<Duo> gk68;
    private final Picker<Duo> gk912;
    private final Picker<Duo> bihar;
    private final Picker<Duo> currentAffairs;
    private final Picker<SamasGroup> samas;
    private final Picker<Confusable> confusables;
    private final Picker<Duo> prep15;
    private final Picker<Duo> prep68;
    private final Picker<Duo> hindi15;
    private final Picker<Duo> hindi68;
    private final Picker<Duo> idioms;
    private final Picker<Duo> quotesSimple;
    private final Picker<Duo> quotesMiddle;
    private final Picker<Duo> quotesSenior;
    private final Picker<Story> stories;
    private final Picker<DayEntry> evergreenDays;

    public ChetnaSatraGenerator(Edition edition, int startRevision) {
        this(edition, startRevision, new Random());
    }

    ChetnaSatraGenerator(Edition edition, int startRevision, Random random) {
        this.edition = edition;
        this.revision = Math.max(0, startRevision);
        this.random = random;
        this.series = new SeriesGenerator(random);

        gk15 = new Picker<>(ContentBank.GK_CLASS_1_5, random);
        gk68 = new Picker<>(ContentBank.GK_CLASS_6_8, random);
        gk912 = new Picker<>(ContentBank.GK_CLASS_9_12, random);
        bihar = new Picker<>(ContentBank.BIHAR_SPECIAL, random);
        currentAffairs = new Picker<>(ContentBank.CURRENT_AFFAIRS, random);
        samas = new Picker<>(ContentBank.SAMAS_GROUPS, random);
        confusables = new Picker<>(ContentBank.ENGLISH_CONFUSABLES, random);
        prep15 = new Picker<>(ContentBank.ENGLISH_PREPOSITIONS_1_5, random);
        prep68 = new Picker<>(ContentBank.ENGLISH_PREPOSITIONS_6_8, random);
        hindi15 = new Picker<>(ContentBank.HINDI_WORDS_1_5, random);
        hindi68 = new Picker<>(ContentBank.HINDI_WORDS_6_8, random);
        idioms = new Picker<>(ContentBank.HINDI_IDIOMS_9_12, random);
        quotesSimple = new Picker<>(ContentBank.QUOTES_SIMPLE, random);
        quotesMiddle = new Picker<>(ContentBank.QUOTES_MIDDLE, random);
        quotesSenior = new Picker<>(ContentBank.QUOTES_SENIOR, random);
        stories = new Picker<>(DayBank.STORIES, random);
        evergreenDays = new Picker<>(DayBank.EVERGREEN, random);

        defineSections();
    }

    public Edition edition() {
        return edition;
    }

    public int revision() {
        return revision;
    }

    // ── Section layout per edition ───────────────────────────────────────────

    private void defineSections() {
        specs.add(new Spec("prayer", "प्रार्थना", true, this::buildPrayer));
        specs.add(new Spec("today", "आज का दिन", false, this::buildToday));
        specs.add(new Spec("thought", "आज का विचार", false, this::buildThought));
        specs.add(new Spec("gk", "सामान्य ज्ञान", false, this::buildGeneralKnowledge));

        if (edition == Edition.PRIMARY) {
            specs.add(new Spec("reasoning", "तर्क ज्ञान", false, this::buildReasoning));
            specs.add(new Spec("language", "भाषा ज्ञान", false, this::buildLanguage));
            specs.add(new Spec("story", "प्रेरक प्रसंग", false, this::buildStory));
        } else {
            specs.add(new Spec("language", "भाषा ज्ञान", false, this::buildLanguage));
            specs.add(new Spec("bihar", "बिहार विशेष", false, this::buildBihar));
            specs.add(new Spec("reasoning", "तर्क ज्ञान", false, this::buildReasoning));
            specs.add(new Spec("affairs", "समसामयिकी", false, this::buildCurrentAffairs));
        }

        specs.add(new Spec("songs", "नीचे दिए गए गीत", true, this::buildSongs));

        for (int i = 0; i < specs.size(); i++) {
            if (!specs.get(i).pinned) refreshable.add(i);
        }
    }

    // ── Generation ───────────────────────────────────────────────────────────

    /**
     * Next sheet. The first call builds everything; later calls refresh a rotating half of the
     * refreshable sections, so the sheet stays recognisable while never repeating itself.
     */
    public Document next() {
        if (current.isEmpty()) {
            for (Spec spec : specs) current.put(spec.key, spec.builder.build());
        } else {
            refreshSlice(Math.max(2, refreshable.size() / 2));
        }

        Document document = assemble();
        // Safety net: pools already avoid immediate repeats, but if a sheet somehow matches the
        // previous one, keep rewriting sections until it does not.
        int guard = 0;
        while (document.signature().equals(lastSignature) && guard++ < refreshable.size()) {
            refreshSlice(2);
            document = assemble();
        }

        lastSignature = document.signature();
        return document;
    }

    /** Rebuilds {@code count} sections starting where the last rotation stopped. */
    private void refreshSlice(int count) {
        int n = refreshable.size();
        for (int i = 0; i < count && n > 0; i++) {
            int specIndex = refreshable.get((rotationCursor + i) % n);
            Spec spec = specs.get(specIndex);
            current.put(spec.key, spec.builder.build());
        }
        if (n > 0) rotationCursor = (rotationCursor + count) % n;
    }

    private Document assemble() {
        revision++;
        Calendar now = Calendar.getInstance();
        String dateLabel = new SimpleDateFormat("dd.MM.yyyy", Locale.ENGLISH).format(now.getTime());
        List<Section> ordered = new ArrayList<>(specs.size());
        for (Spec spec : specs) ordered.add(current.get(spec.key));
        return new Document(edition, dateLabel, revision, System.currentTimeMillis(), ordered);
    }

    // ── Section builders ─────────────────────────────────────────────────────

    private Section buildPrayer() {
        List<Block> blocks = new ArrayList<>();
        int i = 1;
        for (Duo duo : ContentBank.PRAYER) {
            blocks.add(new Pair(i++, duo.first, duo.second));
        }
        return section("prayer", blocks);
    }

    private Section buildSongs() {
        List<Block> blocks = new ArrayList<>();
        for (Duo duo : ContentBank.CLOSING_SONGS) {
            blocks.add(new Line(duo.first + ":", duo.second));
        }
        return section("songs", blocks);
    }

    /** Prefers anniversaries that genuinely fall on today's date, then fills from the evergreen pool. */
    private Section buildToday() {
        Calendar now = Calendar.getInstance();
        int day = now.get(Calendar.DAY_OF_MONTH);
        int month = now.get(Calendar.MONTH) + 1;

        List<DayEntry> chosen = new ArrayList<>(DayBank.forDate(day, month));
        while (chosen.size() < 2) {
            DayEntry filler = evergreenDays.next();
            if (!chosen.contains(filler)) chosen.add(filler);
        }
        if (chosen.size() > 3) chosen = chosen.subList(0, 3);

        List<Block> blocks = new ArrayList<>();
        for (DayEntry entry : chosen) {
            blocks.add(new Para(entry.heading, entry.body));
        }
        return section("today", blocks);
    }

    private Section buildThought() {
        List<Block> blocks = new ArrayList<>();
        if (edition == Edition.PRIMARY) {
            Duo junior = quotesSimple.next();
            Duo middle = quotesMiddle.next();
            blocks.add(new SubHeading("कक्षा 1-5"));
            blocks.add(new Quote(junior.first, junior.second));
            blocks.add(new SubHeading("कक्षा 6-8"));
            blocks.add(new Quote(middle.first, middle.second));
        } else {
            Duo senior = quotesSenior.next();
            blocks.add(new Quote(senior.first, senior.second));
        }
        return section("thought", blocks);
    }

    private Section buildGeneralKnowledge() {
        List<Block> blocks = new ArrayList<>();
        if (edition == Edition.PRIMARY) {
            addGroup(blocks, "कक्षा 1-5", gk15.next(2));
            addGroup(blocks, "कक्षा 6-8", gk68.next(3));
        } else {
            int n = 1;
            for (Duo duo : gk912.next(5)) blocks.add(new Qa(n++, duo.first, duo.second));
        }
        return section("gk", blocks);
    }

    private Section buildBihar() {
        List<Block> blocks = new ArrayList<>();
        int n = 1;
        for (Duo duo : bihar.next(4)) blocks.add(new Qa(n++, duo.first, duo.second));
        return section("bihar", blocks);
    }

    private Section buildCurrentAffairs() {
        List<Block> blocks = new ArrayList<>();
        int n = 1;
        for (Duo duo : currentAffairs.next(4)) blocks.add(new Qa(n++, duo.first, duo.second));
        return section("affairs", blocks);
    }

    private Section buildReasoning() {
        List<Block> blocks = new ArrayList<>();
        if (edition == Edition.PRIMARY) {
            blocks.add(new SubHeading("कक्षा 1-5"));
            addSeries(blocks, SeriesGenerator.Level.EASY, 2);
            blocks.add(new SubHeading("कक्षा 6-8"));
            addSeries(blocks, SeriesGenerator.Level.MEDIUM, 3);
        } else {
            addSeries(blocks, SeriesGenerator.Level.HARD, 4);
        }
        return section("reasoning", blocks);
    }

    private void addSeries(List<Block> blocks, SeriesGenerator.Level level, int count) {
        for (int i = 1; i <= count; i++) {
            SeriesGenerator.Series s = series.next(level);
            blocks.add(new Qa(i, s.question, s.answer));
        }
    }

    private Section buildLanguage() {
        List<Block> blocks = new ArrayList<>();
        if (edition == Edition.PRIMARY) {
            blocks.add(new SubHeading("English — कक्षा 1-5"));
            addPairs(blocks, prep15.next(2));
            blocks.add(new SubHeading("हिंदी — कक्षा 1-5"));
            addPairs(blocks, hindi15.next(2));
            blocks.add(new SubHeading("English — कक्षा 6-8"));
            addPairs(blocks, prep68.next(3));
            blocks.add(new SubHeading("हिंदी — कक्षा 6-8"));
            addPairs(blocks, hindi68.next(3));
        } else {
            SamasGroup group = samas.next();
            blocks.add(new SubHeading(group.name));
            Picker<Duo> examples = new Picker<>(group.examples, random);
            addPairs(blocks, examples.next(Math.min(7, group.examples.size())));

            blocks.add(new SubHeading("English — समरूपी भिन्नार्थक शब्द"));
            int n = 1;
            for (Confusable c : confusables.next(4)) {
                blocks.add(new Pair(n++, c.wordA, c.meaningA));
                blocks.add(new Pair(0, c.wordB, c.meaningB));
            }

            blocks.add(new SubHeading("हिंदी — मुहावरे"));
            addPairs(blocks, idioms.next(3));
        }
        return section("language", blocks);
    }

    private Section buildStory() {
        Story story = stories.next();
        List<Block> blocks = new ArrayList<>();
        boolean first = true;
        for (String paragraph : story.paragraphs) {
            blocks.add(first ? new Para(story.title, paragraph) : new Para(paragraph));
            first = false;
        }
        return section("story", blocks);
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private void addGroup(List<Block> blocks, String heading, List<Duo> items) {
        blocks.add(new SubHeading(heading));
        int n = 1;
        for (Duo duo : items) blocks.add(new Qa(n++, duo.first, duo.second));
    }

    private void addPairs(List<Block> blocks, List<Duo> items) {
        int n = 1;
        for (Duo duo : items) blocks.add(new Pair(n++, duo.first, duo.second));
    }

    private Section section(String key, List<Block> blocks) {
        for (Spec spec : specs) {
            if (spec.key.equals(key)) return new Section(key, spec.title, blocks);
        }
        throw new IllegalStateException("Unknown section key: " + key);
    }
}

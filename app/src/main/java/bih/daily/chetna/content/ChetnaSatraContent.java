package bih.daily.chetna.content;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The shape of a "चेतना सत्र संसाधन सामग्री" sheet.
 *
 * <p>The whole point of this model is that the <em>format</em> is fixed while the content is not:
 * a document is always an ordered list of named sections, and every section is a list of blocks
 * drawn from a small closed set (sub-heading, question/answer, term pair, paragraph, quote, line).
 * A regenerated sheet swaps block content around but can never invent a new layout.
 */
public final class ChetnaSatraContent {

    private ChetnaSatraContent() {
    }

    /** The two sheets the council publishes; they share a format but not a difficulty level. */
    public enum Edition {
        PRIMARY("प्रारंभिक (1-8) विद्यालयों के लिए"),
        SECONDARY("कक्षा 9-12 तक के विद्यालयों के लिए");

        public final String label;

        Edition(String label) {
            this.label = label;
        }
    }

    // ── Blocks ───────────────────────────────────────────────────────────────

    /** One renderable line/paragraph inside a section. */
    public abstract static class Block {
        /** Text used for change detection and for plain-text export. */
        abstract void appendTo(StringBuilder sb);
    }

    /** A bold label inside a section, e.g. "कक्षा 1-5" or "कर्मधारय समास". */
    public static final class SubHeading extends Block {
        public final String text;

        public SubHeading(String text) {
            this.text = text;
        }

        @Override
        void appendTo(StringBuilder sb) {
            sb.append("## ").append(text).append('\n');
        }
    }

    /** A numbered question with its answer. */
    public static final class Qa extends Block {
        public final int number;
        public final String question;
        public final String answer;

        public Qa(int number, String question, String answer) {
            this.number = number;
            this.question = question;
            this.answer = answer;
        }

        @Override
        void appendTo(StringBuilder sb) {
            sb.append(number).append(". ").append(question).append('\n')
                    .append("उत्तर: ").append(answer).append('\n');
        }
    }

    /**
     * A term and its meaning, e.g. "नीलकंठ — नीला है जो कंठ".
     *
     * <p>A {@code number} of {@code 0} renders unnumbered, which is how the second half of a
     * confusable word pair (Marshal / Martial) sits under the first without stealing a number.
     */
    public static final class Pair extends Block {
        public final int number;
        public final String term;
        public final String meaning;

        public Pair(int number, String term, String meaning) {
            this.number = number;
            this.term = term;
            this.meaning = meaning;
        }

        @Override
        void appendTo(StringBuilder sb) {
            if (number > 0) sb.append(number).append(". ");
            sb.append(term).append(" — ").append(meaning).append('\n');
        }
    }

    /** A prose paragraph, optionally introduced by its own bold heading. */
    public static final class Para extends Block {
        public final String heading;
        public final String body;

        public Para(String heading, String body) {
            this.heading = heading;
            this.body = body;
        }

        public Para(String body) {
            this(null, body);
        }

        @Override
        void appendTo(StringBuilder sb) {
            if (heading != null) sb.append("### ").append(heading).append('\n');
            sb.append(body).append('\n');
        }
    }

    /** A quotation with attribution. */
    public static final class Quote extends Block {
        public final String text;
        public final String author;

        public Quote(String text, String author) {
            this.text = text;
            this.author = author;
        }

        @Override
        void appendTo(StringBuilder sb) {
            sb.append('“').append(text).append('”').append('\n')
                    .append("— ").append(author).append('\n');
        }
    }

    /** A "label: value" line, used by the प्रार्थना and गीत sections. */
    public static final class Line extends Block {
        public final String label;
        public final String value;

        public Line(String label, String value) {
            this.label = label;
            this.value = value;
        }

        @Override
        void appendTo(StringBuilder sb) {
            sb.append(label).append(" — ").append(value).append('\n');
        }
    }

    // ── Section / document ───────────────────────────────────────────────────

    public static final class Section {
        /** Stable identifier, used by the generator to refresh sections independently. */
        public final String key;
        public final String title;
        public final List<Block> blocks;

        public Section(String key, String title, List<Block> blocks) {
            this.key = key;
            this.title = title;
            this.blocks = Collections.unmodifiableList(new ArrayList<>(blocks));
        }

        String contentFingerprint() {
            StringBuilder sb = new StringBuilder(key).append('|');
            for (Block b : blocks) b.appendTo(sb);
            return sb.toString();
        }
    }

    public static final class Document {
        public final Edition edition;
        public final String dateLabel;
        /** Increments on every regeneration so the user can see the sheet is new. */
        public final int revision;
        public final long generatedAt;
        public final List<Section> sections;

        public Document(Edition edition, String dateLabel, int revision, long generatedAt,
                        List<Section> sections) {
            this.edition = edition;
            this.dateLabel = dateLabel;
            this.revision = revision;
            this.generatedAt = generatedAt;
            this.sections = Collections.unmodifiableList(new ArrayList<>(sections));
        }

        /**
         * Identity of the <em>content</em> only — revision number and timestamp are excluded on
         * purpose, so two sheets that merely differ by counter still count as identical.
         */
        public String signature() {
            StringBuilder sb = new StringBuilder(edition.name());
            for (Section s : sections) sb.append(s.contentFingerprint());
            return Integer.toHexString(sb.toString().hashCode()) + ":" + sb.length();
        }

        /** Flat text for copy / share. */
        public String toPlainText() {
            StringBuilder sb = new StringBuilder();
            sb.append("राज्य शिक्षा शोध एवं प्रशिक्षण परिषद, बिहार, पटना\n")
                    .append("चेतना सत्र संसाधन सामग्री\n")
                    .append(edition.label).append('\n')
                    .append("दिनांक: ").append(dateLabel).append('\n')
                    .append("संस्करण: ").append(revision).append("\n\n");
            for (Section s : sections) {
                sb.append("# ").append(s.title).append('\n');
                for (Block b : s.blocks) b.appendTo(sb);
                sb.append('\n');
            }
            return sb.toString();
        }
    }
}

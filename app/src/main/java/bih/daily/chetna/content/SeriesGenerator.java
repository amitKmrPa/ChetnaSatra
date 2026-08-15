package bih.daily.chetna.content;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Builds "तर्क ज्ञान" number-series questions arithmetically instead of reading them from a list.
 *
 * <p>Curated banks eventually repeat; a series is generated from random parameters, so this section
 * can stay fresh indefinitely and — because the answer is computed, not typed — it is always
 * correct. Each pattern mirrors one of the shapes used in the printed sheets, e.g. a constant
 * difference for the youngest classes and square-number differences for classes 9-12.
 */
final class SeriesGenerator {

    /** Difficulty bands matching the sheet's own class groupings. */
    enum Level { EASY, MEDIUM, HARD }

    private final Random random;

    SeriesGenerator(Random random) {
        this.random = random;
    }

    /** A rendered series question and its answer. */
    static final class Series {
        final String question;
        final String answer;

        Series(String question, String answer) {
            this.question = question;
            this.answer = answer;
        }
    }

    Series next(Level level) {
        switch (level) {
            case EASY:
                return random.nextBoolean() ? constantDifference(4, 12) : squares(1, 6);
            case MEDIUM:
                switch (random.nextInt(4)) {
                    case 0: return constantDifference(6, 15);
                    case 1: return risingDifference();
                    case 2: return fallingDifference();
                    default: return fibonacciLike();
                }
            case HARD:
            default:
                switch (random.nextInt(5)) {
                    case 0: return squareDifference();
                    case 1: return multiplyByCounting();
                    case 2: return divideByCounting();
                    case 3: return alternatingAddMultiply();
                    default: return cubes(2, 6);
                }
        }
    }

    // ── Patterns ─────────────────────────────────────────────────────────────

    /** a, a+d, a+2d, … — the plainest pattern, used for classes 1-5. */
    private Series constantDifference(int minStep, int maxStep) {
        int start = 5 + random.nextInt(20);
        int step = minStep + random.nextInt(maxStep - minStep + 1);
        List<Long> terms = new ArrayList<>();
        long value = start;
        for (int i = 0; i < 6; i++) {
            terms.add(value);
            value += step;
        }
        return build(terms);
    }

    /** Differences themselves grow by a constant: +d, +2d, +3d … */
    private Series risingDifference() {
        int start = 5 + random.nextInt(15);
        int step = 2 + random.nextInt(5);
        List<Long> terms = new ArrayList<>();
        long value = start;
        for (int i = 0; i < 6; i++) {
            terms.add(value);
            value += (long) step * (i + 1);
        }
        return build(terms);
    }

    /** Same as {@link #risingDifference()} but counting down, so the series shrinks. */
    private Series fallingDifference() {
        int step = 3 + random.nextInt(4);
        // Start high enough that six terms stay positive.
        long start = (long) step * 21 + 10 + random.nextInt(30);
        List<Long> terms = new ArrayList<>();
        long value = start;
        for (int i = 0; i < 6; i++) {
            terms.add(value);
            value -= (long) step * (i + 1);
        }
        return build(terms);
    }

    /** Differences are perfect squares: +2², +3², +4² … */
    private Series squareDifference() {
        int start = 8 + random.nextInt(20);
        List<Long> terms = new ArrayList<>();
        long value = start;
        for (int i = 0; i < 6; i++) {
            terms.add(value);
            int k = i + 2;
            value += (long) k * k;
        }
        return build(terms);
    }

    /** Each term multiplied by the next counting number: ×2, ×3, ×4 … */
    private Series multiplyByCounting() {
        int start = 2 + random.nextInt(8);
        List<Long> terms = new ArrayList<>();
        long value = start;
        for (int i = 0; i < 6; i++) {
            terms.add(value);
            value *= (i + 2);
        }
        return build(terms);
    }

    /** The mirror image: ÷2, ÷3, ÷4 … built backwards so every term stays a whole number. */
    private Series divideByCounting() {
        int tail = 2 + random.nextInt(8);
        List<Long> terms = new ArrayList<>();
        long value = tail;
        terms.add(value);
        for (int i = 0; i < 5; i++) {
            value *= (i + 2);
            terms.add(0, value);
        }
        return build(terms);
    }

    /** Every term is the sum of the two before it. */
    private Series fibonacciLike() {
        long a = 2 + random.nextInt(8);
        long b = a + 1 + random.nextInt(8);
        List<Long> terms = new ArrayList<>();
        terms.add(a);
        terms.add(b);
        for (int i = 0; i < 4; i++) {
            long nextValue = terms.get(terms.size() - 1) + terms.get(terms.size() - 2);
            terms.add(nextValue);
        }
        return build(terms);
    }

    /** Alternates +k and ×2, so the usual "find the difference" reflex is not enough. */
    private Series alternatingAddMultiply() {
        int add = 3 + random.nextInt(6);
        long value = 2 + random.nextInt(6);
        List<Long> terms = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            terms.add(value);
            value = (i % 2 == 0) ? value + add : value * 2;
        }
        return build(terms);
    }

    /** n², (n+1)², … with an optional offset so it is not instantly obvious. */
    private Series squares(int minN, int maxN) {
        int n = minN + random.nextInt(maxN - minN + 1);
        int offset = random.nextInt(3);
        List<Long> terms = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            long k = n + i;
            terms.add(k * k + offset);
        }
        return build(terms);
    }

    /** n³, (n+1)³, … */
    private Series cubes(int minN, int maxN) {
        int n = minN + random.nextInt(maxN - minN + 1);
        List<Long> terms = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            long k = n + i;
            terms.add(k * k * k);
        }
        return build(terms);
    }

    /** Renders the first five terms as the question and keeps the sixth as the answer. */
    private Series build(List<Long> terms) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < terms.size() - 1; i++) {
            sb.append(terms.get(i)).append(", ");
        }
        sb.append('?');
        return new Series(sb.toString(), String.valueOf(terms.get(terms.size() - 1)));
    }
}

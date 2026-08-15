package bih.daily.chetna.content;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/**
 * Deals items from a fixed pool without repeating.
 *
 * <p>A plain {@code Random.nextInt(pool.size())} would happily hand out the same question twice in
 * a row, which is exactly what the sheet must never do. Instead this shuffles the pool into a bag
 * and deals from it; when the bag runs dry it is refilled and reshuffled, with a guard so the first
 * item of the new round is never the item that was just dealt.
 *
 * @param <T> pool item type
 */
final class Picker<T> {

    private final List<T> pool;
    private final List<T> bag = new ArrayList<>();
    private final Random random;
    private T last;

    Picker(List<T> pool, Random random) {
        if (pool.isEmpty()) throw new IllegalArgumentException("Picker needs a non-empty pool");
        this.pool = pool;
        this.random = random;
    }

    /** Next item, guaranteed different from the previous one when the pool has 2+ entries. */
    T next() {
        if (bag.isEmpty()) refill();
        T item = bag.remove(bag.size() - 1);
        last = item;
        return item;
    }

    /** {@code count} distinct items (capped at the pool size). */
    List<T> next(int count) {
        int n = Math.min(count, pool.size());
        List<T> out = new ArrayList<>(n);
        while (out.size() < n) {
            T item = next();
            if (!out.contains(item)) out.add(item);
        }
        return out;
    }

    private void refill() {
        bag.addAll(pool);
        Collections.shuffle(bag, random);
        // The bag is dealt from the tail, so keep the just-used item away from that end.
        int tail = bag.size() - 1;
        if (tail > 0 && bag.get(tail).equals(last)) {
            Collections.swap(bag, 0, tail);
        }
    }
}

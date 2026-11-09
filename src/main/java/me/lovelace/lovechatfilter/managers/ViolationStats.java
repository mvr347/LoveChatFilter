package me.lovelace.lovechatfilter.managers;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Per-day counters of what the filter caught (profanity, spam, caps, ads). Counting is a map increment, so the chat
 * thread never waits for SQL; changed counters are written by {@link #flush()} on an async task and on shutdown.
 */
public final class ViolationStats {

    /** Where the counters survive a restart; {@code save} writes the absolute value of one day and type. */
    public interface Store {
        Map<String, Long> load(LocalDate since);

        void save(LocalDate day, String type, long count);
    }

    public static final int KEEP_DAYS = 90;

    private final Store store;
    private final Map<String, AtomicLong> counters = new ConcurrentHashMap<>();
    private final Set<String> dirty = ConcurrentHashMap.newKeySet();

    public ViolationStats(Store store) {
        this.store = store;
    }

    private static String key(LocalDate day, String type) {
        return day + "|" + type;
    }

    /** Reads the saved counters of the last {@link #KEEP_DAYS} days; called once while the plugin enables. */
    public void load(LocalDate today) {
        counters.clear();
        store.load(today.minusDays(KEEP_DAYS)).forEach((k, v) -> counters.put(k, new AtomicLong(v)));
    }

    public void record(String type, LocalDate day) {
        String key = key(day, type);
        counters.computeIfAbsent(key, k -> new AtomicLong()).incrementAndGet();
        dirty.add(key);
    }

    /** Writes every changed counter; safe to call from any thread. */
    public void flush() {
        for (String key : List.copyOf(dirty)) {
            dirty.remove(key);
            AtomicLong value = counters.get(key);
            if (value == null) continue;
            int split = key.indexOf('|');
            try {
                store.save(LocalDate.parse(key.substring(0, split)), key.substring(split + 1), value.get());
            } catch (RuntimeException failed) {
                dirty.add(key); // try again on the next flush
            }
        }
    }

    /** Totals by type for the last {@code days} days (today included), and the same split per day, oldest first. */
    public Map<String, Object> summary(int days, LocalDate today) {
        int span = Math.max(1, Math.min(days, KEEP_DAYS));
        Map<String, Long> totals = new LinkedHashMap<>();
        List<Map<String, Object>> perDay = new ArrayList<>();
        for (int i = span - 1; i >= 0; i--) {
            LocalDate day = today.minusDays(i);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("day", day.toString());
            long total = 0;
            for (String type : List.of("PROFANITY", "SPAM", "CAPS", "ADS")) {
                AtomicLong counter = counters.get(key(day, type));
                long count = counter == null ? 0 : counter.get();
                row.put(type.toLowerCase(java.util.Locale.ROOT), count);
                totals.merge(type.toLowerCase(java.util.Locale.ROOT), count, Long::sum);
                total += count;
            }
            row.put("total", total);
            perDay.add(row);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("days", span);
        result.put("totals", totals);
        result.put("perDay", perDay);
        return result;
    }
}

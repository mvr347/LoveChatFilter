package me.lovelace.lovechatfilter.managers;

import static org.junit.jupiter.api.Assertions.*;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ViolationStatsTest {

    private static final class MemoryStore implements ViolationStats.Store {
        final Map<String, Long> saved = new HashMap<>();
        boolean failing;

        @Override
        public Map<String, Long> load(LocalDate since) {
            return new HashMap<>(saved);
        }

        @Override
        public void save(LocalDate day, String type, long count) {
            if (failing) throw new IllegalStateException("db down");
            saved.put(day + "|" + type, count);
        }
    }

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 9);

    @Test
    void countsPerDayAndTypeAndSummarisesOldestFirst() {
        var stats = new ViolationStats(new MemoryStore());
        stats.record("PROFANITY", TODAY);
        stats.record("PROFANITY", TODAY);
        stats.record("SPAM", TODAY.minusDays(1));
        var summary = stats.summary(3, TODAY);
        @SuppressWarnings("unchecked")
        var perDay = (List<Map<String, Object>>) summary.get("perDay");
        assertEquals(3, perDay.size());
        assertEquals("2026-11-07", perDay.get(0).get("day"));
        assertEquals(1L, perDay.get(1).get("spam"));
        assertEquals(2L, perDay.get(2).get("profanity"));
        @SuppressWarnings("unchecked")
        var totals = (Map<String, Long>) summary.get("totals");
        assertEquals(2L, totals.get("profanity"));
        assertEquals(1L, totals.get("spam"));
        assertEquals(0L, totals.get("caps"));
    }

    @Test
    void flushWritesAbsoluteValuesAndSurvivesARestart() {
        var store = new MemoryStore();
        var stats = new ViolationStats(store);
        stats.record("CAPS", TODAY);
        stats.record("CAPS", TODAY);
        stats.flush();
        assertEquals(2L, store.saved.get("2026-11-09|CAPS"));
        var reloaded = new ViolationStats(store);
        reloaded.load(TODAY);
        reloaded.record("CAPS", TODAY);
        reloaded.flush();
        assertEquals(3L, store.saved.get("2026-11-09|CAPS"));
    }

    @Test
    void aFailedWriteIsRetriedOnTheNextFlush() {
        var store = new MemoryStore();
        var stats = new ViolationStats(store);
        stats.record("ADS", TODAY);
        store.failing = true;
        stats.flush();
        assertTrue(store.saved.isEmpty());
        store.failing = false;
        stats.flush();
        assertEquals(1L, store.saved.get("2026-11-09|ADS"));
    }

    @Test
    void overlappingFlushesNeverLeaveAnOlderValueBehind() throws Exception {
        var store = new MemoryStore();
        var stats = new ViolationStats(store);
        var pool = java.util.concurrent.Executors.newFixedThreadPool(4);
        try {
            for (int round = 0; round < 200; round++) {
                stats.record("SPAM", TODAY);
                var a = pool.submit(stats::flush);
                stats.record("SPAM", TODAY);
                var b = pool.submit(stats::flush);
                a.get();
                b.get();
            }
            stats.flush();
        } finally {
            pool.shutdownNow();
        }
        assertEquals(400L, store.saved.get("2026-11-09|SPAM"));
    }

    @Test
    void theSpanIsClamped() {
        var stats = new ViolationStats(new MemoryStore());
        assertEquals(1, stats.summary(0, TODAY).get("days"));
        assertEquals(ViolationStats.KEEP_DAYS, stats.summary(10_000, TODAY).get("days"));
    }
}

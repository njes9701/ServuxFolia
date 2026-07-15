package dev.njes.lep;

import java.util.concurrent.atomic.LongAdder;

public final class RuntimeStats {
    private final LongAdder captured = new LongAdder();
    private final LongAdder applied = new LongAdder();
    private final LongAdder rejected = new LongAdder();
    private final LongAdder buildChecked = new LongAdder();
    private final LongAdder prevalidated = new LongAdder();
    private final LongAdder resynced = new LongAdder();

    public void captured() {
        captured.increment();
    }

    public void applied() {
        applied.increment();
    }

    public void rejected() {
        rejected.increment();
    }

    public void buildChecked() {
        buildChecked.increment();
    }

    public void prevalidated() {
        prevalidated.increment();
    }

    public void resynced() {
        resynced.increment();
    }

    public long capturedCount() {
        return captured.sum();
    }

    public long appliedCount() {
        return applied.sum();
    }

    public long rejectedCount() {
        return rejected.sum();
    }

    public long buildCheckedCount() {
        return buildChecked.sum();
    }

    public long prevalidatedCount() {
        return prevalidated.sum();
    }

    public long resyncedCount() {
        return resynced.sum();
    }
}

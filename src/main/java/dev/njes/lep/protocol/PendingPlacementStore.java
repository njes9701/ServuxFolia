package dev.njes.lep.protocol;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Iterator;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class PendingPlacementStore {
    private final Map<UUID, ArrayDeque<PendingPlacement>> queues = new ConcurrentHashMap<>();
    private volatile long ttlNanos;
    private volatile int maxPerPlayer;

    public PendingPlacementStore(Duration ttl, int maxPerPlayer) {
        reconfigure(ttl, maxPerPlayer);
    }

    public void reconfigure(Duration ttl, int maxPerPlayer) {
        this.ttlNanos = ttl.toNanos();
        this.maxPerPlayer = maxPerPlayer;
    }

    public void enqueue(UUID playerId, PendingPlacement placement) {
        ArrayDeque<PendingPlacement> queue = queues.computeIfAbsent(playerId, ignored -> new ArrayDeque<>());

        synchronized (queue) {
            removeExpired(queue, placement.createdAtNanos());
            while (queue.size() >= maxPerPlayer) {
                queue.removeFirst();
            }
            queue.addLast(placement);
        }
    }

    public Optional<PendingPlacement> consume(
            UUID playerId,
            int hand,
            PendingPlacement.Position clicked,
            PendingPlacement.Position placed,
            long nowNanos
    ) {
        ArrayDeque<PendingPlacement> queue = queues.get(playerId);
        if (queue == null) {
            return Optional.empty();
        }

        synchronized (queue) {
            removeExpired(queue, nowNanos);
            Iterator<PendingPlacement> iterator = queue.iterator();

            while (iterator.hasNext()) {
                PendingPlacement placement = iterator.next();
                if (placement.matches(hand, clicked, placed)) {
                    iterator.remove();
                    return Optional.of(placement);
                }
            }

            return Optional.empty();
        }
    }

    public Optional<PendingPlacement> find(
            UUID playerId,
            int hand,
            PendingPlacement.Position position,
            long nowNanos
    ) {
        ArrayDeque<PendingPlacement> queue = queues.get(playerId);
        if (queue == null) {
            return Optional.empty();
        }

        synchronized (queue) {
            removeExpired(queue, nowNanos);
            return queue.stream()
                    .filter(placement -> placement.matches(hand, position, null))
                    .findFirst();
        }
    }

    public void clear(UUID playerId) {
        queues.remove(playerId);
    }

    public void clearAll() {
        queues.clear();
    }

    public int pendingCount() {
        int count = 0;
        for (ArrayDeque<PendingPlacement> queue : queues.values()) {
            synchronized (queue) {
                count += queue.size();
            }
        }
        return count;
    }

    public void cleanup(long nowNanos) {
        queues.forEach((playerId, queue) -> {
            synchronized (queue) {
                removeExpired(queue, nowNanos);
            }
        });
    }

    private void removeExpired(ArrayDeque<PendingPlacement> queue, long nowNanos) {
        while (!queue.isEmpty() && nowNanos - queue.peekFirst().createdAtNanos() > ttlNanos) {
            queue.removeFirst();
        }
    }
}

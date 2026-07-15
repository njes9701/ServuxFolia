package dev.njes.servuxfolia.security;

/** Small synchronized token bucket; one instance is owned by one player. */
public final class TokenBucket {
    private final double capacity;
    private final double tokensPerNano;
    private double tokens;
    private long lastRefill;

    public TokenBucket(double capacity, double tokensPerSecond) {
        if (capacity <= 0 || tokensPerSecond <= 0) {
            throw new IllegalArgumentException("limits must be positive");
        }
        this.capacity = capacity;
        this.tokensPerNano = tokensPerSecond / 1_000_000_000.0D;
        this.tokens = capacity;
        this.lastRefill = System.nanoTime();
    }

    public synchronized boolean tryAcquire() {
        long now = System.nanoTime();
        tokens = Math.min(capacity, tokens + (now - lastRefill) * tokensPerNano);
        lastRefill = now;
        if (tokens < 1.0D) {
            return false;
        }
        tokens -= 1.0D;
        return true;
    }
}

package dev.njes.servuxfolia.security;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TokenBucketTest {
    @Test
    void rejectsAfterCapacityIsConsumed() {
        TokenBucket bucket = new TokenBucket(2, 0.0001D);
        assertTrue(bucket.tryAcquire());
        assertTrue(bucket.tryAcquire());
        assertFalse(bucket.tryAcquire());
    }
}

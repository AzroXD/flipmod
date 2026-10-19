package dev.flipmod;

/** One flip suggestion received from the Coflnet socket. */
public record Flip(String uuid, String name, long cost, long target, String text, long receivedAtMs) {

    /** Expected profit in coins, or -1 if the feed did not include enough data. */
    public long profit() {
        return (target > 0 && cost > 0) ? target - cost : -1;
    }

    public boolean stale(long maxAgeMs) {
        return System.currentTimeMillis() - receivedAtMs > maxAgeMs;
    }
}

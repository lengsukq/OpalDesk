package io.github.lengsukq.opaldesk.daemon.stream;

import java.util.ArrayDeque;

/**
 * Bounded power-of-two/tiered byte[] pool for high-FPS encoded video frames.
 * Eliminates per-frame 10-25 MB/s short-lived heap allocations at 60-120fps without risking
 * unbounded native/JVM memory growth on Android 17 MemoryLimiter.
 */
public final class VideoFrameBufferPool {
    private static final int[] BUCKET_SIZES = new int[]{
            16 * 1024,
            64 * 1024,
            128 * 1024,
            256 * 1024,
            512 * 1024
    };
    private static final int MAX_PER_BUCKET = 6;

    @SuppressWarnings("unchecked")
    private final ArrayDeque<byte[]>[] buckets = new ArrayDeque[BUCKET_SIZES.length];

    public VideoFrameBufferPool() {
        for (int i = 0; i < buckets.length; i++) {
            buckets[i] = new ArrayDeque<>(MAX_PER_BUCKET);
        }
    }

    /**
     * Acquires a reusable byte[] of at least {@code minBytes}. Callers must track the exact
     * valid payload length separately and pass the returned array back to {@link #release(byte[])}
     * once transmission completes or the frame is dropped.
     */
    public byte[] acquire(int minBytes) {
        if (minBytes <= 0) return OpalCastProtocol.EMPTY_BYTES;
        int idx = findBucketForMinSize(minBytes);
        if (idx < 0) {
            return new byte[minBytes];
        }
        synchronized (buckets[idx]) {
            byte[] cached = buckets[idx].pollFirst();
            if (cached != null) {
                return cached;
            }
        }
        return new byte[BUCKET_SIZES[idx]];
    }

    /**
     * Returns a previously acquired buffer to its matching bucket if within capacity.
     */
    public void release(byte[] buf) {
        if (buf == null || buf.length == 0) return;
        int idx = findExactBucket(buf.length);
        if (idx < 0) return;
        synchronized (buckets[idx]) {
            if (buckets[idx].size() < MAX_PER_BUCKET) {
                buckets[idx].offerFirst(buf);
            }
        }
    }

    public int pooledCountForSize(int minBytes) {
        int idx = findBucketForMinSize(minBytes);
        if (idx < 0) return 0;
        synchronized (buckets[idx]) {
            return buckets[idx].size();
        }
    }

    public void clear() {
        for (int i = 0; i < buckets.length; i++) {
            synchronized (buckets[i]) {
                buckets[i].clear();
            }
        }
    }

    private static int findBucketForMinSize(int minBytes) {
        for (int i = 0; i < BUCKET_SIZES.length; i++) {
            if (minBytes <= BUCKET_SIZES[i]) {
                return i;
            }
        }
        return -1;
    }

    private static int findExactBucket(int len) {
        for (int i = 0; i < BUCKET_SIZES.length; i++) {
            if (BUCKET_SIZES[i] == len) {
                return i;
            }
        }
        return -1;
    }
}

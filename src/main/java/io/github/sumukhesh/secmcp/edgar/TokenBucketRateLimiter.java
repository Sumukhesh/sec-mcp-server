package io.github.sumukhesh.secmcp.edgar;

import java.time.Duration;
import java.util.function.LongSupplier;

import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

/**
 * Non-blocking token bucket. Each caller reserves a token; when the bucket is empty the
 * reservation goes into debt and the caller is delayed until its token would have been refilled.
 * Reserving under a lock and waiting outside it keeps callers in FIFO order without parking threads.
 *
 * <p>Capacity is one token, so requests are evenly spaced ({@code 1/rate} apart). A larger bucket
 * would allow bursts: with capacity 8 and 8 tokens/s refill, a one-second window could see up to
 * 16 requests, which would breach the SEC's 10 req/s limit.
 */
public final class TokenBucketRateLimiter {

    private static final double CAPACITY = 1.0;

    private final double tokensPerNano;
    private final LongSupplier nanoClock;
    private final Scheduler scheduler;

    private double tokens = CAPACITY;
    private long lastRefillNanos;

    public TokenBucketRateLimiter(int permitsPerSecond) {
        this(permitsPerSecond, System::nanoTime, Schedulers.parallel());
    }

    TokenBucketRateLimiter(int permitsPerSecond, LongSupplier nanoClock, Scheduler scheduler) {
        if (permitsPerSecond <= 0) {
            throw new IllegalArgumentException("permitsPerSecond must be positive");
        }
        this.tokensPerNano = permitsPerSecond / 1_000_000_000.0;
        this.nanoClock = nanoClock;
        this.scheduler = scheduler;
        this.lastRefillNanos = nanoClock.getAsLong();
    }

    /** Completes once the caller may send one request. Subscribe once per request (and per retry). */
    public Mono<Void> acquire() {
        return Mono.defer(() -> {
            Duration wait = reserve();
            return wait.isZero() ? Mono.empty() : Mono.delay(wait, scheduler).then();
        });
    }

    /** Takes one token, returning how long the caller must wait before using it. */
    synchronized Duration reserve() {
        long now = nanoClock.getAsLong();
        tokens = Math.min(CAPACITY, tokens + (now - lastRefillNanos) * tokensPerNano);
        lastRefillNanos = now;
        tokens -= 1.0;
        if (tokens >= 0) {
            return Duration.ZERO;
        }
        return Duration.ofNanos((long) Math.ceil(-tokens / tokensPerNano));
    }
}

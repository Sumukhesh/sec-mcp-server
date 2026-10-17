package io.github.sumukhesh.secmcp.edgar;

import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;
import reactor.test.StepVerifier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class TokenBucketRateLimiterTest {

    private final AtomicLong nanos = new AtomicLong();
    private final TokenBucketRateLimiter limiter =
            new TokenBucketRateLimiter(8, nanos::get, Schedulers.immediate());

    @Test
    void firstRequestIsImmediate() {
        assertThat(limiter.reserve()).isZero();
    }

    @Test
    void backToBackRequestsAreSpacedAtTheConfiguredRate() {
        assertThat(limiter.reserve()).isZero();
        assertThat(limiter.reserve()).isEqualTo(Duration.ofMillis(125));
        assertThat(limiter.reserve()).isEqualTo(Duration.ofMillis(250));
        assertThat(limiter.reserve()).isEqualTo(Duration.ofMillis(375));
    }

    @Test
    void idleTimeRefillsButNeverBeyondOneToken() {
        limiter.reserve();
        nanos.addAndGet(Duration.ofSeconds(10).toNanos());

        assertThat(limiter.reserve()).isZero();
        assertThat(limiter.reserve()).isEqualTo(Duration.ofMillis(125));
    }

    @Test
    void partialRefillShortensTheWait() {
        limiter.reserve();
        nanos.addAndGet(Duration.ofMillis(100).toNanos());

        assertThat(limiter.reserve()).isEqualTo(Duration.ofMillis(25));
    }

    @Test
    void neverExceedsRateInAnyOneSecondWindow() {
        // Simulate 100 back-to-back callers and compute when each one is allowed to fire.
        long[] fireAt = new long[100];
        for (int i = 0; i < fireAt.length; i++) {
            fireAt[i] = limiter.reserve().toNanos();
        }
        long oneSecond = Duration.ofSeconds(1).toNanos();
        for (int i = 0; i < fireAt.length; i++) {
            int inWindow = 0;
            for (long t : fireAt) {
                if (t >= fireAt[i] && t < fireAt[i] + oneSecond) {
                    inWindow++;
                }
            }
            assertThat(inWindow).isLessThanOrEqualTo(8);
        }
    }

    @Test
    void acquireDelaysSubscribersOnVirtualTime() {
        StepVerifier.withVirtualTime(() -> {
                    Scheduler virtual = Schedulers.parallel(); // replaced by VirtualTimeScheduler
                    TokenBucketRateLimiter onVirtualTime = new TokenBucketRateLimiter(
                            8, () -> virtual.now(TimeUnit.NANOSECONDS), virtual);
                    return Flux.range(0, 3).concatMap(i -> onVirtualTime.acquire().thenReturn(i));
                })
                .expectSubscription()
                .expectNext(0)
                .expectNoEvent(Duration.ofMillis(125))
                .expectNext(1)
                .expectNoEvent(Duration.ofMillis(125))
                .expectNext(2)
                .verifyComplete();
    }

    @Test
    void rejectsNonPositiveRate() {
        assertThatIllegalArgumentException().isThrownBy(() -> new TokenBucketRateLimiter(0));
    }
}

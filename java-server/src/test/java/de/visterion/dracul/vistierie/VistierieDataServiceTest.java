package de.visterion.dracul.vistierie;

import de.visterion.dracul.strigoi.StrigoiDetail;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class VistierieDataServiceTest {

    private static VistierieProperties props() {
        return new VistierieProperties("http://localhost", 5.00, List.of(
                new VistierieProperties.TierConfig("Frugal", "haiku", 1.00),
                new VistierieProperties.TierConfig("Standard", "sonnet", 2.50),
                new VistierieProperties.TierConfig("Premium", "opus", 1.50)
        ));
    }

    @Test
    void aggregatesExpectedShape() {
        var service = new VistierieDataService(props(), new MockVistierieClient(), 30);

        var data = service.getData();

        assertThat(data.tiers()).hasSize(3);
        assertThat(data.spendingByAgent()).isNotEmpty();
        assertThat(data.dailySpend30d()).hasSize(30);
        assertThat(data.monthlyBudgetUsd()).isEqualTo(5.00);
    }

    /**
     * Proves the per-strigoi detail calls run concurrently rather than serially.
     * The fake's getStrigoiDetail blocks on a barrier whose party count equals the
     * number of strigoi. If the calls were serial, the first one would never see the
     * others arrive and would time out; parallel execution trips the barrier and all
     * calls complete.
     */
    @Test
    void fetchesStrigoiDetailsInParallel() throws Exception {
        var strigoi = new MockVistierieClient().listStrigoi();
        var barrier = new CyclicBarrier(strigoi.size());
        var brokeBarrier = new AtomicInteger(0);

        var client = new MockVistierieClient() {
            @Override
            public Optional<StrigoiDetail> getStrigoiDetail(String name) {
                try {
                    barrier.await(5, TimeUnit.SECONDS);
                } catch (TimeoutException | BrokenBarrierException e) {
                    brokeBarrier.incrementAndGet();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return super.getStrigoiDetail(name);
            }
        };

        var service = new VistierieDataService(props(), client, 30);
        var data = service.getData();

        assertThat(brokeBarrier.get())
                .as("all strigoi detail calls must reach the barrier concurrently (no serial timeout)")
                .isZero();
        assertThat(data.spendingByAgent()).isNotEmpty();
    }

    @Test
    void cachesWithinTtl() {
        var calls = new AtomicInteger(0);
        var client = new MockVistierieClient() {
            @Override
            public List<LlmProvider> getProviders() {
                calls.incrementAndGet();
                return super.getProviders();
            }
        };
        var service = new VistierieDataService(props(), client, 30);

        service.getData();
        service.getData();
        service.getData();

        assertThat(calls.get())
                .as("within TTL the upstream is hit only once")
                .isEqualTo(1);
    }

    @Test
    void refetchesWhenTtlZero() {
        var calls = new AtomicInteger(0);
        var client = new MockVistierieClient() {
            @Override
            public List<LlmProvider> getProviders() {
                calls.incrementAndGet();
                return super.getProviders();
            }
        };
        var service = new VistierieDataService(props(), client, 0);

        service.getData();
        service.getData();

        assertThat(calls.get())
                .as("TTL of 0 disables caching")
                .isEqualTo(2);
    }
}

package com.fraudguard.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.fraudguard.config.TestcontainersConfig;
import com.fraudguard.dto.TransactionEvent;
import com.fraudguard.dto.VelocityFeatures;
import com.fraudguard.entity.TransactionType;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@AutoConfigureMockMvc   // not used here; keeps the setup identical to the other test classes
@ActiveProfiles("test") // so all of them share ONE app + containers
@Import(TestcontainersConfig.class)
class TransactionCacheServiceTest {

    @Autowired TransactionCacheService cache;
    @Autowired StringRedisTemplate redis;

    // All event times are in the past, so the service's "no future timestamps" rule never changes them
    private static final Instant T0 = Instant.now().minus(Duration.ofHours(3));

    @Test
    void firstTransactionHasNoHistory() {
        VelocityFeatures f = cache.recordAndComputeFeatures(
                txn(newAccount(), "100.00", T0, "dev-1", "m-1", "IN"));

        assertThat(f.transactionCount5m()).isEqualTo(1);
        assertThat(f.transactionCount1h()).isEqualTo(1);
        assertThat(f.amountSum1h()).isEqualByComparingTo("100");
        assertThat(f.historyCount()).isZero();
        assertThat(f.amountZScore()).isNull();                       // not enough history: no z-score
        assertThat(f.secondsSincePreviousTransaction()).isNull();
        assertThat(f.newDevice()).isTrue();
        assertThat(f.newMerchant()).isTrue();
        assertThat(f.countryChanged()).isFalse();
    }

    @Test
    void windowsSlideWithEventTime() {
        String acct = newAccount();
        cache.recordAndComputeFeatures(txn(acct, "10", T0, null, null, null));
        cache.recordAndComputeFeatures(txn(acct, "20", T0.plus(Duration.ofMinutes(10)), null, null, null));
        cache.recordAndComputeFeatures(txn(acct, "30", T0.plus(Duration.ofMinutes(58)), null, null, null));
        VelocityFeatures f = cache.recordAndComputeFeatures(
                txn(acct, "40", T0.plus(Duration.ofMinutes(62)), null, null, null));

        // 1h window = (+2min, +62min]: the first transaction has dropped out
        assertThat(f.transactionCount1h()).isEqualTo(3);
        assertThat(f.amountSum1h()).isEqualByComparingTo("90");     // 20 + 30 + 40
        // 5min window = (+57min, +62min]: only the last two
        assertThat(f.transactionCount5m()).isEqualTo(2);
        assertThat(f.amountSum5m()).isEqualByComparingTo("70");     // 30 + 40
    }

    @Test
    void replayedTransactionIsNotCountedTwice() {
        String acct = newAccount();
        TransactionEvent.Payload first = txn(acct, "50", T0, null, null, null);

        VelocityFeatures original = cache.recordAndComputeFeatures(first);
        VelocityFeatures replay = cache.recordAndComputeFeatures(first);   // same transaction again
        assertThat(replay).isEqualTo(original);

        VelocityFeatures next = cache.recordAndComputeFeatures(
                txn(acct, "60", T0.plus(Duration.ofMinutes(1)), null, null, null));
        assertThat(next.transactionCount1h()).isEqualTo(2);         // counted once, not three times
        assertThat(next.amountSum1h()).isEqualByComparingTo("110");
    }

    @Test
    void amountDeviationNeedsEnoughHistory() {
        String acct = newAccount();
        String[] baseline = {"100", "102", "98", "101", "99"};
        VelocityFeatures fifth = null;
        for (int i = 0; i < baseline.length; i++) {
            fifth = cache.recordAndComputeFeatures(
                    txn(acct, baseline[i], T0.plus(Duration.ofMinutes(i)), null, null, null));
        }
        // The 5th transaction was compared with only 4 earlier ones: not enough for a z-score
        assertThat(fifth.historyCount()).isEqualTo(4);
        assertThat(fifth.amountZScore()).isNull();

        // The 6th is compared with 5 earlier ones (average 100, std dev about 1.58)
        VelocityFeatures outlier = cache.recordAndComputeFeatures(
                txn(acct, "500", T0.plus(Duration.ofMinutes(5)), null, null, null));
        assertThat(outlier.historyCount()).isEqualTo(5);
        assertThat(outlier.averageAmount()).isEqualByComparingTo("100");
        assertThat(outlier.amountZScore()).isGreaterThan(200.0);
    }

    @Test
    void newDeviceAndNewMerchantAreDetected() {
        String acct = newAccount();
        VelocityFeatures first = cache.recordAndComputeFeatures(txn(acct, "10", T0, "dev-A", "m-1", "IN"));
        assertThat(first.newDevice()).isTrue();
        assertThat(first.newMerchant()).isTrue();

        VelocityFeatures second = cache.recordAndComputeFeatures(
                txn(acct, "10", T0.plusSeconds(60), "dev-A", "m-2", "IN"));
        assertThat(second.newDevice()).isFalse();   // dev-A was seen before
        assertThat(second.newMerchant()).isTrue();  // m-2 is new

        VelocityFeatures third = cache.recordAndComputeFeatures(
                txn(acct, "10", T0.plusSeconds(120), "dev-B", "m-1", "IN"));
        assertThat(third.newDevice()).isTrue();     // dev-B is new
        assertThat(third.newMerchant()).isFalse();  // m-1 was seen before
    }

    @Test
    void timeSincePreviousAndCountryChange() {
        String acct = newAccount();
        cache.recordAndComputeFeatures(txn(acct, "10", T0, null, null, "IN"));

        VelocityFeatures second = cache.recordAndComputeFeatures(
                txn(acct, "10", T0.plusSeconds(90), null, null, "US"));
        assertThat(second.secondsSincePreviousTransaction()).isEqualTo(90);
        assertThat(second.countryChanged()).isTrue();

        VelocityFeatures third = cache.recordAndComputeFeatures(
                txn(acct, "10", T0.plusSeconds(120), null, null, "US"));
        assertThat(third.secondsSincePreviousTransaction()).isEqualTo(30);
        assertThat(third.countryChanged()).isFalse();
    }

    @Test
    void outOfOrderEventDoesNotMoveLatestBackwards() {
        String acct = newAccount();
        cache.recordAndComputeFeatures(txn(acct, "10", T0.plus(Duration.ofMinutes(10)), null, null, null));

        // An OLDER transaction arrives late: "time since previous" is unknown, not negative
        VelocityFeatures late = cache.recordAndComputeFeatures(txn(acct, "10", T0, null, null, null));
        assertThat(late.secondsSincePreviousTransaction()).isNull();

        // The latest known transaction is still the one at +10min
        VelocityFeatures next = cache.recordAndComputeFeatures(
                txn(acct, "10", T0.plus(Duration.ofMinutes(11)), null, null, null));
        assertThat(next.secondsSincePreviousTransaction()).isEqualTo(60);
    }

    @Test
    void concurrentTransactionsAreRecordedAtomically() throws Exception {
        String acct = newAccount();
        ExecutorService pool = Executors.newFixedThreadPool(16);
        List<Future<VelocityFeatures>> futures = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            Instant at = T0.plusSeconds(i);
            futures.add(pool.submit(() ->
                    cache.recordAndComputeFeatures(txn(acct, "10.00", at, "dev-1", "m-1", "IN"))));
        }
        for (Future<VelocityFeatures> future : futures) {
            future.get(30, TimeUnit.SECONDS);
        }
        pool.shutdown();

        // If any update had been lost to a race, these numbers would be lower than expected
        VelocityFeatures last = cache.recordAndComputeFeatures(
                txn(acct, "10.00", T0.plusSeconds(60), "dev-1", "m-1", "IN"));
        assertThat(last.historyCount()).isEqualTo(50);
        assertThat(last.transactionCount1h()).isEqualTo(51);
        assertThat(last.amountSum1h()).isEqualByComparingTo("510");
    }

    @Test
    void everyKeyHasAnExpiry() {
        String acct = newAccount();
        TransactionEvent.Payload p = txn(acct, "10", T0, "dev-1", "m-1", "IN");
        cache.recordAndComputeFeatures(p);

        // getExpire returns the remaining lifetime in seconds (-1 would mean "never expires")
        assertThat(redis.getExpire(TransactionCacheService.windowKey(acct)))
                .isBetween(1L, TransactionCacheService.WINDOW_TTL_SECONDS);
        assertThat(redis.getExpire(TransactionCacheService.statsKey(acct)))
                .isBetween(1L, TransactionCacheService.PROFILE_TTL_SECONDS);
        assertThat(redis.getExpire(TransactionCacheService.devicesKey(acct)))
                .isBetween(1L, TransactionCacheService.PROFILE_TTL_SECONDS);
        assertThat(redis.getExpire(TransactionCacheService.lastKey(acct)))
                .isBetween(1L, TransactionCacheService.PROFILE_TTL_SECONDS);
        assertThat(redis.getExpire(TransactionCacheService.markerKey(p.transactionId())))
                .isBetween(1L, TransactionCacheService.MARKER_TTL_SECONDS);
    }

    // ---------- helpers ----------

    // Each test uses its own account, so tests can't affect each other
    private static String newAccount() {
        return "acc-" + UUID.randomUUID();
    }

    private static TransactionEvent.Payload txn(String account, String amount, Instant at,
                                                String device, String merchant, String country) {
        return new TransactionEvent.Payload(UUID.randomUUID(), account, merchant, TransactionType.PAYMENT,
                new BigDecimal(amount), "INR", device, country, at);
    }
}
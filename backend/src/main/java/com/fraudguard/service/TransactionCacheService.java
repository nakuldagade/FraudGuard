package com.fraudguard.service;

import com.fraudguard.dto.TransactionEvent;
import com.fraudguard.dto.VelocityFeatures;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

// All Redis access for fraud features lives here.
//
// REDIS KEYS (PREFIX + ...). "v1" is the key-layout version, so the layout can change later.
//   txn:{transactionId}          String  the features computed for that transaction (replay protection), 24h
//   acct:{accountId}:window      ZSET    recent transactions "txnId|amount", scored by event time, 2h
//   acct:{accountId}:stats       Hash    n / mean / m2: running amount statistics, 30 days
//   acct:{accountId}:devices     Set     device IDs seen, 30 days
//   acct:{accountId}:merchants   Set     merchant IDs seen, 30 days
//   acct:{accountId}:last        Hash    time + country of the latest transaction, 30 days
// Every key has a TTL, so idle accounts disappear from Redis by themselves.
//
// Single Redis node assumed (local demo). A Redis Cluster would need the keys grouped by hash tag.
@Service
@RequiredArgsConstructor
public class TransactionCacheService {

    static final String PREFIX = "fraudguard:v1:";
    static final long WINDOW_TTL_SECONDS = 2 * 60 * 60;            // we read 1h, keep 2h as a safety margin
    static final long PROFILE_TTL_SECONDS = 30L * 24 * 60 * 60;    // long-term behaviour
    static final long MARKER_TTL_SECONDS = 24 * 60 * 60;           // how long a replay is recognised
    static final int MIN_HISTORY = 5;                              // earlier transactions needed for a z-score

    private final StringRedisTemplate redis;
    private final DefaultRedisScript<String> recordTransactionScript;

    /**
     * Records the transaction in Redis and returns its velocity features. Safe to call twice for the
     * same transaction: the second call changes nothing and returns the same answer.
     */
    public VelocityFeatures recordAndComputeFeatures(TransactionEvent.Payload p) {
        String account = p.accountId();

        // Windows are measured on the transaction's own time. A timestamp in the future is
        // clamped to "now", so one bad value can't wipe the account's recent history.
        long eventMillis = Math.min(p.occurredAt().toEpochMilli(), System.currentTimeMillis());

        String raw = redis.execute(
                recordTransactionScript,
                List.of(markerKey(p.transactionId()), windowKey(account), statsKey(account),
                        devicesKey(account), merchantsKey(account), lastKey(account)),
                p.transactionId().toString(),
                p.amount().toPlainString(),
                String.valueOf(eventMillis),
                nullToEmpty(p.deviceId()),
                nullToEmpty(p.merchantId()),
                nullToEmpty(p.country()),
                String.valueOf(WINDOW_TTL_SECONDS),
                String.valueOf(PROFILE_TTL_SECONDS),
                String.valueOf(MARKER_TTL_SECONDS),
                String.valueOf(MIN_HISTORY));

        if (raw == null) {
            throw new IllegalStateException("Redis script returned no result");
        }
        return parse(raw);
    }

    // The script answers with 12 comma-separated values; see record_transaction.lua for the order.
    private static VelocityFeatures parse(String raw) {
        String[] f = raw.split(",");
        return new VelocityFeatures(
                Integer.parseInt(f[0]),
                Integer.parseInt(f[1]),
                money(f[2]),
                money(f[3]),
                Integer.parseInt(f[4]),
                money(f[5]),
                money(f[6]),
                "NA".equals(f[7]) ? null : Double.valueOf(f[7]),
                "1".equals(f[8]),
                "1".equals(f[9]),
                "NA".equals(f[10]) ? null : (long) Double.parseDouble(f[10]),
                "1".equals(f[11]));
    }

    private static BigDecimal money(String s) {
        return new BigDecimal(s).setScale(2, RoundingMode.HALF_UP);
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    // ---------- key builders (package-private so tests can check TTLs) ----------

    static String markerKey(UUID transactionId) { return PREFIX + "txn:" + transactionId; }
    static String windowKey(String accountId)   { return PREFIX + "acct:" + accountId + ":window"; }
    static String statsKey(String accountId)    { return PREFIX + "acct:" + accountId + ":stats"; }
    static String devicesKey(String accountId)  { return PREFIX + "acct:" + accountId + ":devices"; }
    static String merchantsKey(String accountId) { return PREFIX + "acct:" + accountId + ":merchants"; }
    static String lastKey(String accountId)     { return PREFIX + "acct:" + accountId + ":last"; }
}
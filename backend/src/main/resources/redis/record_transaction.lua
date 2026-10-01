-- Records one transaction for an account and returns that transaction's velocity features.
-- Everything happens inside ONE script, and Redis runs a script without interruption,
-- so concurrent transactions can never interleave their reads and writes (atomic).
--
-- KEYS[1] marker     String: the features computed for this transaction (makes replays idempotent)
-- KEYS[2] window     Sorted set: member "txnId|amount", score = event time in milliseconds
-- KEYS[3] stats      Hash: n, mean, m2 (running amount statistics, Welford's algorithm)
-- KEYS[4] devices    Set of device IDs seen for this account
-- KEYS[5] merchants  Set of merchant IDs seen for this account
-- KEYS[6] last       Hash: ts, country of the account's latest transaction
--
-- ARGV[1] txnId          ARGV[2] amount          ARGV[3] eventTimeMs
-- ARGV[4] deviceId ('' = unknown)    ARGV[5] merchantId ('' = unknown)    ARGV[6] country ('' = unknown)
-- ARGV[7] windowTtlSec   ARGV[8] profileTtlSec   ARGV[9] markerTtlSec   ARGV[10] minHistory
--
-- Returns ONE comma-separated string, in this order:
--   count5m, count1h, sum5m, sum1h, historyCount, mean, stddev, zscore|NA,
--   newDevice(0/1), newMerchant(0/1), secondsSincePrevious|NA, countryChanged(0/1)

-- Replay of a transaction we already recorded: return the stored answer, change nothing.
local existing = redis.call('GET', KEYS[1])
if existing then
  return existing
end

local txnId = ARGV[1]
local amountStr = ARGV[2]
local amount = tonumber(amountStr)
local ts = tonumber(ARGV[3])
local deviceId = ARGV[4]
local merchantId = ARGV[5]
local country = ARGV[6]
local windowTtl = tonumber(ARGV[7])
local profileTtl = tonumber(ARGV[8])
local markerTtl = tonumber(ARGV[9])
local minHistory = tonumber(ARGV[10])

local FIVE_MIN = 5 * 60 * 1000
local ONE_HOUR = 60 * 60 * 1000

-- Formats a number as a plain integer string (avoids "1.7e+12" for big millisecond values)
local function whole(n)
  return string.format('%.0f', n)
end

-- 1) SLIDING WINDOW: drop entries older than one hour, add this transaction, read the window.
--    The window is (ts - 1h, ts] and INCLUDES this transaction.
redis.call('ZREMRANGEBYSCORE', KEYS[2], '-inf', whole(ts - ONE_HOUR))
redis.call('ZADD', KEYS[2], whole(ts), txnId .. '|' .. amountStr)
redis.call('EXPIRE', KEYS[2], windowTtl)
local entries = redis.call('ZRANGEBYSCORE', KEYS[2], '(' .. whole(ts - ONE_HOUR), whole(ts), 'WITHSCORES')

local count5m, sum5m, count1h, sum1h = 0, 0, 0, 0
for i = 1, #entries, 2 do
  local amt = tonumber(string.match(entries[i], '|([^|]+)$'))
  local score = tonumber(entries[i + 1])
  count1h = count1h + 1
  sum1h = sum1h + amt
  if score > ts - FIVE_MIN then
    count5m = count5m + 1
    sum5m = sum5m + amt
  end
end

-- 2) AMOUNT BASELINE (long-term mean / standard deviation), read BEFORE adding this amount
--    so the transaction is compared with the account's past behaviour, not with itself.
local n = tonumber(redis.call('HGET', KEYS[3], 'n') or '0')
local mean = tonumber(redis.call('HGET', KEYS[3], 'mean') or '0')
local m2 = tonumber(redis.call('HGET', KEYS[3], 'm2') or '0')
local stddev = 0
if n > 1 then
  stddev = math.sqrt(m2 / (n - 1))
end
-- Too little history (or zero spread) means no meaningful z-score: report NA instead of guessing
local zscore = 'NA'
if n >= minHistory and stddev > 0 then
  zscore = tostring((amount - mean) / stddev)
end

-- Welford's online algorithm: updates mean and variance one value at a time
local newN = n + 1
local delta = amount - mean
local newMean = mean + delta / newN
local newM2 = m2 + delta * (amount - newMean)
redis.call('HSET', KEYS[3], 'n', tostring(newN),
  'mean', string.format('%.17g', newMean), 'm2', string.format('%.17g', newM2))
redis.call('EXPIRE', KEYS[3], profileTtl)

-- 3) NEW DEVICE / NEW MERCHANT: SADD returns 1 only when the value was not in the set yet
local newDevice = 0
if deviceId ~= '' then
  if redis.call('SADD', KEYS[4], deviceId) == 1 then newDevice = 1 end
  redis.call('EXPIRE', KEYS[4], profileTtl)
end
local newMerchant = 0
if merchantId ~= '' then
  if redis.call('SADD', KEYS[5], merchantId) == 1 then newMerchant = 1 end
  redis.call('EXPIRE', KEYS[5], profileTtl)
end

-- 4) TIME SINCE PREVIOUS TRANSACTION and COUNTRY CHANGE.
--    If events arrive out of order (this one is OLDER than the latest we know), report NA.
local lastTs = tonumber(redis.call('HGET', KEYS[6], 'ts') or '-1')
local lastCountry = redis.call('HGET', KEYS[6], 'country') or ''
local secondsSince = 'NA'
local countryChanged = 0
if lastTs >= 0 and lastTs <= ts then
  secondsSince = tostring((ts - lastTs) / 1000)
  if country ~= '' and lastCountry ~= '' and country ~= lastCountry then
    countryChanged = 1
  end
end
if ts >= lastTs then   -- only ever move "latest" forward in time
  local keepCountry = lastCountry
  if country ~= '' then keepCountry = country end
  redis.call('HSET', KEYS[6], 'ts', whole(ts), 'country', keepCountry)
  redis.call('EXPIRE', KEYS[6], profileTtl)
end

-- 5) Build the answer, remember it for replays, return it
local result = table.concat({
  tostring(count5m), tostring(count1h), tostring(sum5m), tostring(sum1h),
  tostring(n), tostring(mean), tostring(stddev), zscore,
  tostring(newDevice), tostring(newMerchant), secondsSince, tostring(countryChanged)
}, ',')
redis.call('SET', KEYS[1], result, 'EX', markerTtl)
return result
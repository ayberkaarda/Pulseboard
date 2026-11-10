-- Atomic token bucket.
-- KEYS[1]  bucket key (hash with fields "tokens" and "ts")
-- ARGV[1]  capacity (tokens)
-- ARGV[2]  refill period in milliseconds (time to refill a full bucket)
-- ARGV[3]  tokens requested
-- Returns { allowed (0|1), retry_after_ms }.
-- Time comes from the Redis server, so every api-core instance shares one clock.

local capacity = tonumber(ARGV[1])
local period_ms = tonumber(ARGV[2])
local requested = tonumber(ARGV[3])

local server_time = redis.call('TIME')
local now_ms = tonumber(server_time[1]) * 1000 + math.floor(tonumber(server_time[2]) / 1000)

local state = redis.call('HMGET', KEYS[1], 'tokens', 'ts')
local tokens = tonumber(state[1])
local last_ms = tonumber(state[2])
if tokens == nil or last_ms == nil then
    tokens = capacity
    last_ms = now_ms
end

local rate_per_ms = capacity / period_ms
local elapsed_ms = math.max(0, now_ms - last_ms)
tokens = math.min(capacity, tokens + elapsed_ms * rate_per_ms)

local allowed = 0
local retry_after_ms = 0
if tokens >= requested then
    tokens = tokens - requested
    allowed = 1
else
    retry_after_ms = math.ceil((requested - tokens) / rate_per_ms)
end

redis.call('HSET', KEYS[1], 'tokens', tostring(tokens), 'ts', tostring(now_ms))
-- An untouched bucket is full again after one period, so it can be dropped then.
redis.call('PEXPIRE', KEYS[1], period_ms)

return { allowed, retry_after_ms }

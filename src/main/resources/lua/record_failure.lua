-- KEYS[1] = circuit hash key
-- ARGV[1] = current timestamp (ms)
-- ARGV[2] = failure threshold -> e.g. 5

local key = KEYS[1]
local now = tonumber(ARGV[1])
local threshold = tonumber(ARGV[2])

local state = redis.call('HGET', key, 'state')

if state == 'HALF_OPEN' then
    redis.call('HSET', key, 'state', 'OPEN', 'openedAt', now, 'failureCount', 0, 'successCount', 0, 'trialCount', 0)
    return 'OPEN'
end

local failureCount = tonumber(redis.call('HINCRBY', key, 'failureCount', 1))
redis.call('HSET', key, 'state', 'CLOSED')

if failureCount >= threshold then
    redis.call('HSET', key, 'state', 'OPEN', 'openedAt', now, 'failureCount', 0)
    return 'OPEN'
end

return 'CLOSED'
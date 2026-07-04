-- KEYS[1] = circuit hash key
-- ARGV[1] = half-open max trial requests -> e.g. 3

local key = KEYS[1]
local maxTrials = tonumber(ARGV[1])

local state = redis.call('HGET', key, 'state')

if not state or state == 'CLOSED' then
    redis.call('HSET', key, 'state', 'CLOSED', 'failureCount', 0)
    return 'CLOSED'
end

if state == 'HALF_OPEN' then
    local successCount = tonumber(redis.call('HINCRBY', key, 'successCount', 1))
    if successCount >= maxTrials then
        redis.call('HSET', key, 'state', 'CLOSED', 'failureCount', 0, 'successCount', 0, 'trialCount', 0, 'openedAt', 0)
        return 'CLOSED'
    end
    return 'HALF_OPEN'
end

return state
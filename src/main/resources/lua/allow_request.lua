-- KEYS[1] = circuit hash key, e.g. circuit:tenant123:api.backend.com
-- ARGV[1] = current timestamp (ms)
-- ARGV[2] = cooldown period (ms)  -> e.g. 30000
-- ARGV[3] = half-open max trial requests -> e.g. 3

local key = KEYS[1]
local now = tonumber(ARGV[1])
local cooldown = tonumber(ARGV[2])
local maxTrials = tonumber(ARGV[3])

local state = redis.call('HGET', key, 'state')

if not state then
    redis.call('HSET', key, 'state', 'CLOSED', 'failureCount', 0, 'openedAt', 0, 'trialCount', 0)
    return 'ALLOW'
end

if state == 'CLOSED' then
    return 'ALLOW'
end

if state == 'OPEN' then
    local openedAt = tonumber(redis.call('HGET', key, 'openedAt'))
    if (now - openedAt) >= cooldown then
        redis.call('HSET', key, 'state', 'HALF_OPEN', 'trialCount', 1)
        return 'ALLOW'
    else
        return 'DENY'
    end
end

if state == 'HALF_OPEN' then
    local trialCount = tonumber(redis.call('HGET', key, 'trialCount'))
    if trialCount < maxTrials then
        redis.call('HINCRBY', key, 'trialCount', 1)
        return 'ALLOW'
    else
        return 'DENY'
    end
end

return 'DENY'
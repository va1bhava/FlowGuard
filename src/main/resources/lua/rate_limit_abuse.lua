-- KEYS[1] = counter key, e.g. abuse:429count:tenant123
-- KEYS[2] = alert cooldown key, e.g. abuse:429alerted:tenant123
-- ARGV[1] = window seconds (e.g. 60)
-- ARGV[2] = threshold (e.g. 100)
-- ARGV[3] = cooldown seconds (e.g. 300)

local countKey = KEYS[1]
local alertKey = KEYS[2]
local windowSeconds = tonumber(ARGV[1])
local threshold = tonumber(ARGV[2])
local cooldownSeconds = tonumber(ARGV[3])

local count = redis.call('INCR', countKey)
if count == 1 then
    redis.call('EXPIRE', countKey, windowSeconds)
end

if count >= threshold then
    local alreadyAlerted = redis.call('GET', alertKey)
    if not alreadyAlerted then
        redis.call('SET', alertKey, '1', 'EX', cooldownSeconds)
        return 'ALERT'
    end
end

return 'OK'
-- 秒杀请求已经通过 Redis Lua，但 RocketMQ 投递失败时执行原子回滚。
-- KEYS[1]：库存 Key；KEYS[2]：一人一单集合；ARGV[1]：用户 id。
local stockKey = KEYS[1]
local orderKey = KEYS[2]
local userId = ARGV[1]

if(redis.call('sismember', orderKey, userId) == 1) then
    redis.call('srem', orderKey, userId)
    redis.call('incrby', stockKey, 1)
    return 1
end

-- 回滚请求幂等：如果预占已经被其他流程处理，不重复增加库存。
return 0

package com.nexusops.shared.ratelimit;

import java.util.List;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

/** Atomic token bucket in Redis (spec §9). Uses Redis server time, so app-node clock skew is irrelevant. */
@Component
public class RedisRateLimiter {

    public record Decision(boolean allowed, long retryAfterSeconds) {}

    @SuppressWarnings("rawtypes")
    private static final RedisScript<List> TOKEN_BUCKET = new DefaultRedisScript<>("""
            local capacity = tonumber(ARGV[1])
            local window = tonumber(ARGV[2])
            local t = redis.call('TIME')
            local now = tonumber(t[1]) * 1000 + math.floor(tonumber(t[2]) / 1000)
            local rate = capacity / window
            local data = redis.call('HMGET', KEYS[1], 'tokens', 'ts')
            local tokens = tonumber(data[1])
            local ts = tonumber(data[2])
            if tokens == nil or ts == nil then
              tokens = capacity
              ts = now
            end
            tokens = math.min(capacity, tokens + math.max(0, now - ts) * rate)
            local allowed = 0
            local retry = 0
            if tokens >= 1 then
              tokens = tokens - 1
              allowed = 1
            else
              retry = math.ceil(((1 - tokens) / rate) / 1000)
              if retry < 1 then retry = 1 end
            end
            redis.call('HSET', KEYS[1], 'tokens', tostring(tokens), 'ts', tostring(now))
            redis.call('PEXPIRE', KEYS[1], window)
            return {allowed, retry}
            """, List.class);

    private final StringRedisTemplate redis;

    RedisRateLimiter(StringRedisTemplate redis) {
        this.redis = redis;
    }

    public Decision tryConsume(String key, RateLimitRule rule) {
        try {
            List<?> result = redis.execute(TOKEN_BUCKET, List.of(key),
                    String.valueOf(rule.capacity()), String.valueOf(rule.window().toMillis()));
            long allowed = ((Number) result.get(0)).longValue();
            long retry = ((Number) result.get(1)).longValue();
            return new Decision(allowed == 1L, retry);
        } catch (DataAccessException e) {
            throw new RateLimiterUnavailableException(e);
        }
    }
}

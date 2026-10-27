package com.hmdp.utils;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

@Component
public class RedisIdWorker {
    private StringRedisTemplate redisTemplate;
    private static final long BEGIN_TIMESTAMP = 1694502400000L;
    private static final int COUNT_BITS = 32;
    public RedisIdWorker(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }
    public Long nextId(String keyPrefix) {
        // 1.生成时间戳
        LocalDateTime now = LocalDateTime.now();
        long nowSecond = now.toEpochSecond(ZoneOffset.UTC);
        long timestamp = nowSecond - BEGIN_TIMESTAMP;
        // 2.生成序列号
        // 2.1 获取当前日期，精确到天
        String date = now.format(DateTimeFormatter.ofPattern("yyyy:MM:dd"));
        long sequence = redisTemplate.opsForValue().increment("icr" + keyPrefix+ ":" + date);
        // 3.拼接时间戳和序列号
        long uniqueId = timestamp << COUNT_BITS | sequence;
        return uniqueId;
    }

}

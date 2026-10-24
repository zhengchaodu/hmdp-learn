package com.hmdp.utils;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hmdp.entity.Shop;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static com.hmdp.utils.RedisConstants.*;

@Slf4j
@Component
public class CacheClient {

    private final StringRedisTemplate stringRedisTemplate;

    public CacheClient(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    // 方法1.将任意Java对象序列化为json并存储在string类型的key中，并可以设置TTL
    public void set(String key, Object value, Long time, TimeUnit unit) {
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value), time, unit);
    }
    // 方法2.将任意Java对象序列化为json并存储在string类型的key中，并可以设置逻辑过期时间，用于解决缓存击穿问题
    public void setWithLogicalExpire(String key, Object value, Long time,TimeUnit unit) {
        // 1.设置逻辑过期时间
        RedisData redisData = new RedisData();
        redisData.setData(value);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(unit.toSeconds(time)));
        // 2.写入redis
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(redisData));
    }
    // 方法3.根据指定的key获取缓存,并利用空值缓存解决缓存击穿问题
    public <R,ID> R queryWithPassThrough(String keyPrefix, ID id, Class <R> type, Function<ID,R> dbFallback, Long time, TimeUnit unit) {
        String key = keyPrefix + id;
        // 1. 从redis查询信息
        String json = stringRedisTemplate.opsForValue().get(key);
        // 2. 判断是否存在
        if (StrUtil.isNotBlank(json)) {
            // 3. 存在，直接返回
            R r = JSONUtil.toBean(json, type);
            return r;
        }
        // 9.判断命中的是否是空值
        // 不是真实数据（上面判断的），也不是null，那就只能是""咯
        if (json != null) {
            // 返回错误信息
            return null;
        }
        // 4. 不存在，根据id查询数据库
        R r = dbFallback.apply(id);
        // 5.不存在，返回错误
        if (r == null) {
            // 8.将空值写入redis，防止缓存穿透
            stringRedisTemplate.opsForValue().set(key, "", CACHE_NULL_TTL, java.util.concurrent.TimeUnit.MINUTES);
            return null;
        }
        // 6.存在，写入redis
        this.set(key, r, time, unit);
        // 7. 返回
        return r;
    }
    // 方法4.根据指定的key获取缓存,并利用逻辑过期解决缓存击穿问题
    // 逻辑过期解决缓存击穿问题
    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);
    public <R,ID> R queryWithLogicExpire(String keyPrefix ,ID id, Class<R> type,String lockPrefix , Function<ID,R> dbFallback, Long time, TimeUnit unit) {
        String key = keyPrefix + id;
        // 1.从redis查询信息
        String json = stringRedisTemplate.opsForValue().get(key);
        // 2.判断缓存是否存在
        if (StrUtil.isBlank(json)) {
            // 3.缓存为空值("")或 key 不存在，直接返回 null
            // 注意：逻辑过期方案要求热点 key 已预热，key 不存在属于异常/非热点场景
            return null;
        }
        // 4.存在，但不是Blank，需要先把json反序列化为对象
        RedisData redisData = JSONUtil.toBean(json, RedisData.class);
        R r = JSONUtil.toBean((JSONObject) redisData.getData(), type);
        LocalDateTime expireTime = redisData.getExpireTime();
        // 5.判断是否过期
        if (LocalDateTime.now().isBefore(expireTime)) {
            // 5.1.未过期，直接返回店铺信息
            return r;
        }
        // 6.已过期，需要缓存重建
        // 6.1.获取互斥锁
        String lockKey = lockPrefix + id;
        boolean isLock = this.tryLock(lockKey);
        // 6.2.判断是否获取锁成功
        if (isLock) {
            // 6.3.成功，开启独立线程，实现缓存重建
            CACHE_REBUILD_EXECUTOR.submit(() -> {
                try {
                    // 6.4.查询数据库
                    R r1 = dbFallback.apply(id);
                    // 6.5.写入redis
                    this.setWithLogicalExpire(key, r1, time, unit);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    unlock(lockKey);
                }
            });
        }
        // 7.失败，返回过期的商铺信息
        return r;
    }
    // 写两个方法用于上锁和释放
    private boolean tryLock(String key) {
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 10, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag);
    }
    private void unlock(String key) {
        stringRedisTemplate.delete(key);
    }
}

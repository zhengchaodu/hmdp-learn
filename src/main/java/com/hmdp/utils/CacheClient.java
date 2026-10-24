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
    // 方法3.根据指定的key获取缓存,并利用空值缓存解决缓存穿透问题
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
        // 改进，如果 R 是 String、集合等，或 data 不是 JSONObject，
        // 会 ClassCastException 或类型转换错误。通用方法不能这样强转。
        // 4.存在，但不是Blank，需要先把json反序列化为RedisData对象
        RedisData redisData = JSONUtil.toBean(json, RedisData.class);
        Object data = redisData.getData();
        if (data == null) {
            return null;
        }
        // 通用反序列化，不依赖data的具体类型
        R r = JSONUtil.toBean(JSONUtil.toJsonStr(data), type);
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
                    if(r1 == null) {
                        // 数据库不存在，写空值缓存，防止反复重建/穿透
                        stringRedisTemplate.opsForValue().set(key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
                    } else {
                        // 6.5.写入redis
                        this.setWithLogicalExpire(key, r1, time, unit);
                    }

                } catch (Exception e) {
                    // log.error("缓存重建失败, key={}", key, e);
                    throw new RuntimeException(e);
                } finally {
                    unlock(lockKey);
                }
            });
        }
        // 7.失败，返回过期的商铺信息
        return r;
    }
    // 使用锁的方式
    public <R,ID> R queryWithMutex_Func1(String keyPrefix,ID id, Class<R> type, String lockPrefix, Function<ID,R> dbFallback, Long time, TimeUnit unit) {
        String key = keyPrefix + id;
        // 1.从redis查询信息
        String json = stringRedisTemplate.opsForValue().get(key);
        // 2.判断是否存在
        if (StrUtil.isNotBlank(json)) {
            // 3.存在，直接返回
            R r = JSONUtil.toBean(json, type);
            return r;
        }
        // 判断命中的是否是空值
        if (json != null) {
            // 返回错误信息
            return null;
        }
        // 4.实现缓存重建
        // 4.1.获取互斥锁
        String lockKey = lockPrefix + id;
        R r = null;
        try {
            boolean isLock = this.tryLock(lockKey);
            // 4.2.判断是否获取成功
            if (!isLock) {
                // 4.3.失败，则休眠并重试
                Thread.sleep(50);
                // 递归查询有栈溢出的问题，这里暂时就不改了
                return this.queryWithMutex_Func1(keyPrefix, id, type, lockPrefix, dbFallback, time, unit);
            }
            // 4.4.成功，根据id查询数据库
            r = dbFallback.apply(id);
            // 5.不存在，返回错误
            if (r == null) {
                // 6.将空值写入redis，防止缓存穿透
                stringRedisTemplate.opsForValue().set(key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
                // 不能用 this.set(key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
                // 因为这个方法会把""序列化成json，变成"\"\""，而不是""，所以要直接用stringRedisTemplate
                return null;
            }
            // 7.存在，写入redis
            this.set(key, r, time, unit);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        } finally {
            // 8.释放锁
            unlock(lockKey);
        }
        // 9.返回
        return r;
    }
    // 上面那种方法有问题
    // return queryWithMutex(id) 的意图是“没抢到锁就等一会再来”，这个重试思路本身没错；
    //但它写在 try 块里，return 会触发 finally，导致没抢到锁的线程也执行了 unlock，把别人的锁删了。
    // 在递归过程中会反复触发unlock，尽管那个lock已经被别的拿走了，最终导致锁失效，缓存击穿。
    public <R,ID> R queryWithMutex_Func2(String keyPrefix,ID id, Class<R> type, String lockPrefix, Function<ID,R> dbFallback, Long time, TimeUnit unit) {
        String key = keyPrefix + id;
        // 1.从redis查询信息
        String json = stringRedisTemplate.opsForValue().get(key);
        // 2.判断是否存在
        if (StrUtil.isNotBlank(json)) {
            // 3.存在，直接返回
            R r = JSONUtil.toBean(json, type);
            return r;
        }
        // 判断命中的是否是空值
        if (json != null) {
            // 返回错误信息
            return null;
        }
        // 4.实现缓存重建
        // 4.1.获取互斥锁
        String lockKey = lockPrefix + id;
        // 改进，queryWithMutex_Func2 的 while(true) 没有最大重试/超时
        int maxRetry = 200; // 50ms * 200 ≈ 10s
        R r = null;
        for(int i = 0; i < maxRetry; i++) {
            boolean isLock = this.tryLock(lockKey);
            // 4.2.判断是否获取成功
            if (!isLock) {
                // 4.3.失败，则休眠并重试
                try {
                    Thread.sleep(50);
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
                continue;
            }
            try {
                // 4.4.成功，根据id查询数据库
                // 改进，其实这里也可以再查一次redis，防止在获取锁的过程中，其他线程已经把数据写入redis了
                String cacheJson = stringRedisTemplate.opsForValue().get(key);
                if (StrUtil.isNotBlank(cacheJson)) {
                    // 是真的数据，直接返回
                    return JSONUtil.toBean(cacheJson, type);
                }
                // 不是真的数据，又不是null，那就只能是""的空值缓存咯，所以直接返回null
                if (cacheJson != null) {
                    return null;
                }

                r = dbFallback.apply(id);
                // 5.不存在，返回错误
                if (r == null) {
                    // 6.将空值写入redis，防止缓存穿透
                    stringRedisTemplate.opsForValue().set(key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
                    // 不能用 this.set(key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
                    // 因为这个方法会把""序列化成json，变成"\"\""，而不是""，所以要直接用stringRedisTemplate
                    return null;
                }
                // 7.存在，写入redis
               this.set(key, r, time, unit);
            } finally {
                // 8.释放锁
                unlock(lockKey);
            }
            break;
        }
        // 9.
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

package com.hmdp.service.impl;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import cn.hutool.log.Log;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisData;
import org.apache.tomcat.jni.Time;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;

import java.time.LocalDateTime;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);
    @Override
    public Result queryById(Long id) {
        // 缓存穿透方法
        // Shop shop = queryWithPassThrough(id);
        // 使用互斥锁解决缓存击穿
        Shop shop = queryWithMutex_Func1(id);
        if (shop == null) {
            return Result.fail("商铺不存在");
        }
        // 7.返回
        return Result.ok(shop);
    }
    // 使用锁的方式
    public Shop queryWithMutex_Func1(Long id) {
        String key = CACHE_SHOP_KEY + id;
        // 1.从redis查询商铺信息
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        // 2.判断是否存在
        if (StrUtil.isNotBlank(shopJson)) {
            // 3.存在，直接返回
            Shop shop = JSONUtil.toBean(shopJson, Shop.class);
            return shop;
        }
        // 判断命中的是否是空值
        if (shopJson != null) {
            // 返回错误信息
            return null;
        }
        // 4.实现缓存重建
        // 4.1.获取互斥锁
        String lockKey = LOCK_SHOP_KEY + id;
        Shop shop = null;
        try {
            boolean isLock = this.tryLock(lockKey);
            // 4.2.判断是否获取成功
            if (!isLock) {
                // 4.3.失败，则休眠并重试
                Thread.sleep(50);
                return queryWithMutex_Func1(id);
            }
            // 4.4.成功，根据id查询数据库
            shop = getById(id);
            // 5.不存在，返回错误
            if (shop == null) {
                // 6.将空值写入redis，防止缓存穿透
                stringRedisTemplate.opsForValue().set(key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
                return null;
            }
            // 7.存在，写入redis
            stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(shop), CACHE_SHOP_TTL, TimeUnit.MINUTES);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        } finally {
            // 8.释放锁
            unlock(lockKey);
        }
        // 9.返回
        return shop;
    }
    // 上面那种方法有问题
    // return queryWithMutex(id) 的意图是“没抢到锁就等一会再来”，这个重试思路本身没错；
    //但它写在 try 块里，return 会触发 finally，导致没抢到锁的线程也执行了 unlock，把别人的锁删了。
    // 在递归过程中会反复触发unlock，尽管那个lock已经被别的拿走了，最终导致锁失效，缓存击穿。
    public Shop queryWithMutex_Func2(Long id) {
        String key = CACHE_SHOP_KEY + id;
        // 1.从redis查询商铺信息
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        // 2.判断是否存在
        if (StrUtil.isNotBlank(shopJson)) {
            // 3.存在，直接返回
            Shop shop = JSONUtil.toBean(shopJson, Shop.class);
            return shop;
        }
        // 判断命中的是否是空值
        if (shopJson != null) {
            // 返回错误信息
            return null;
        }
        // 4.实现缓存重建
        // 4.1.获取互斥锁
        String lockKey = LOCK_SHOP_KEY + id;
        Shop shop = null;
        while (true) {
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
                shop = getById(id);
                // 5.不存在，返回错误
                if (shop == null) {
                    // 6.将空值写入redis，防止缓存穿透
                    stringRedisTemplate.opsForValue().set(key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
                    return null;
                }
                // 7.存在，写入redis
                stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(shop), CACHE_SHOP_TTL, TimeUnit.MINUTES);
            } finally {
                // 8.释放锁
                unlock(lockKey);
            }
            break;
        }
        // 9.返回
        return shop;
    }
    // 缓存穿透时，我们可以使用缓存空值的方式来解决
    public Shop queryWithPassThrough(Long id){
        // 1. 从redis查询商铺信息
        String shopJson = stringRedisTemplate.opsForValue().get(CACHE_SHOP_KEY + id);
        // 2. 判断是否存在
        if (StrUtil.isNotBlank(shopJson)) {
            // 3. 存在，直接返回
            Shop shop = JSONUtil.toBean(shopJson, Shop.class);
            return shop;
        }
        // 9.判断命中的是否是空值
        // 不是真实数据（上面判断的），也不是null，那就只能是""咯
        if (shopJson != null) {
            // 返回错误信息
            return null;
        }
        // 4. 不存在，根据id查询数据库
        Shop shop = getById(id);
        // 5.不存在，返回错误
        if (shop == null) {
            // 8.将空值写入redis，防止缓存穿透
            stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id, "", CACHE_NULL_TTL, java.util.concurrent.TimeUnit.MINUTES);
            return null;
        }
        // 6.存在，写入redis
        stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY+ id, JSONUtil.toJsonStr(shop),CACHE_SHOP_TTL,java.util.concurrent.TimeUnit.MINUTES);
        // 7. 返回
        return shop;
    }
    // 逻辑过期解决缓存击穿问题
    public Shop queryWithLogicExpire(Long id) {
        String key = CACHE_SHOP_KEY + id;
        // 1.从redis查询商铺信息
        String json = stringRedisTemplate.opsForValue().get(key);
        // 2.判断缓存是否存在
        if (StrUtil.isBlank(json)) {
            // 3.存在，但是是Blank，说明是空值，直接返回null
            return null;
        }
        // 4.存在，但不是Blank，需要先把json反序列化为对象
        RedisData redisData = JSONUtil.toBean(json, RedisData.class);
        Shop shop = JSONUtil.toBean((JSONObject) redisData.getData(), Shop.class);
        LocalDateTime expireTime = redisData.getExpireTime();
        // 5.判断是否过期
        if (LocalDateTime.now().isBefore(expireTime)) {
            // 5.1.未过期，直接返回店铺信息
            return shop;
        }
        // 6.已过期，需要缓存重建
        // 6.1.获取互斥锁
        String lockKey = LOCK_SHOP_KEY + id;
        boolean isLock = this.tryLock(lockKey);
        // 6.2.判断是否获取锁成功
        if (isLock) {
            // 6.3.成功，开启独立线程，实现缓存重建
            CACHE_REBUILD_EXECUTOR.submit(() -> {
                try {
                    this.saveShop2Redis(id, CACHE_SHOP_TTL);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    unlock(lockKey);
                }
            });
        }
        // 7.失败，返回过期的商铺信息
        return shop;
    }
    // 写一个方法用于将shop信息封装为RediData并存入redis
    public void saveShop2Redis(Long id, Long expireSeconds) {
        // 1.查询店铺数据
        Shop shop = getById(id);
        // 2.封装为RedisData对象
        RedisData redisData = new RedisData();
        redisData.setData(shop);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(expireSeconds));
        // 3.写入redis
        stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id, JSONUtil.toJsonStr(redisData));
    }
    // 写两个方法用于上锁和释放
    private boolean tryLock(String key) {
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 10, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag);
    }
    private void unlock(String key) {
        stringRedisTemplate.delete(key);
    }
    @Override
    @Transactional
    public Result updateShop(Shop shop) {
        // 1. 判断是否存在
        Long id = shop.getId();
        if (id == null) {
            return Result.fail("商铺id不能为空");
        }
        // 2. 更新数据库
        updateById(shop);
        // 3. 删除缓存
        stringRedisTemplate.delete(CACHE_SHOP_KEY + id);
        // TODO 延时双删策略
        // 我们的是一个单体项目，所以更新数据库和删除缓存在同一段代码里
        // 如果是分布式系统，这里可能就通过MQ通知另一个系统来删除缓存
        return Result.ok();
    }
}

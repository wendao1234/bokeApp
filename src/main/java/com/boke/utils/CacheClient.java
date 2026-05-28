package com.hmdp.utils;

import cn.hutool.core.util.StrUtil;
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

/*
  缓存客户端
 */
@Component
@Slf4j
public class CacheClient {
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    // 开启线程池
    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);

    /**
     * 缓存数据
     *
     * @param key   键
     * @param value 值
     * @param time  时间
     * @param unit  时间单位
     */
    public void set(String key, Object value, Long time, TimeUnit unit) {
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value), time, unit);
    }

    /**
     * 设置逻辑过期
     *
     * @param key   键
     * @param value 值
     * @param time  时间
     * @param unit  时间单位
     */
    public void setWithLogicalExpire(String key, Object value, Long time, TimeUnit unit) {
        // 封装逻辑过期时间
        RedisData redisData = new RedisData();
        redisData.setData(value);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(unit.toSeconds(time)));
        // 写入Redis
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(redisData));
    }

    /**
     * 缓存穿透
     *
     * @param id            对象id
     * @param type          对象类型
     * @param queryFunction 查询函数
     * @param time          时间
     * @param unit          时间单位
     * @return 对象信息
     */
    public  <R, ID> R queryWithPassThrough(String keyPrefix, ID id, Class<R> type, Function<ID, R> queryFunction, Long time, TimeUnit unit) {
        // 先从Redis查询，如果存在，则返回，否则从数据库中查询并缓存到Redis中
        String key = keyPrefix + id;
        String Json = stringRedisTemplate.opsForValue().get(key);
        if (StrUtil.isNotBlank(Json)) {
            // 存在，则返回
            R r = JSONUtil.toBean(Json, type);
            return r;
        }
        if (Json != null) {
            // 用于处理空对象，避免重复查询数据库
            return null;
        }
        R r = queryFunction.apply(id);
        if (r == null) {
            // 不存在，返回空对象
            // 防止缓存穿透，缓存空对象
            this.set(key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
            return null;
        }
        // 存在，写入redis并返回
        this.set(key, r, time, unit);
        return r;
    }


  /**
     * 互斥锁解决缓存击穿问题
     *
       * @param id            对象id
       * @param type          对象类型
       * @param queryFunction 查询函数
       * @param time          时间
       * @param unit          时间单位
       * @return 对象信息
     */
    public  <R, ID> R queryWithMutex(String keyPrefix, ID id, Class<R> type, Function<ID, R> queryFunction, Long time, TimeUnit unit) {
        // 先从Redis查询，如果存在，则返回，否则从数据库中查询并缓存到Redis中
        String key = keyPrefix + id;
        String Json = stringRedisTemplate.opsForValue().get(key);
        if (StrUtil.isNotBlank(Json)) {
            // 存在，则返回
            R r = JSONUtil.toBean(Json, type);
            return r;
        }
        if (Json != null) {
            // 用于处理空对象，避免重复查询数据库
            return null;
        }
        // 实现缓存重建
        // 1.获取锁
        String lockId = LOCK_SHOP_KEY + id;
        R r = null;
        try {
            boolean isLock = tryLock(lockId);
            // 2.判断锁是否已存在
            if (!isLock) {
                // 获取锁失败，则休眠并重试
                Thread.sleep(50);
                return queryWithMutex(keyPrefix, id, type, queryFunction, time, unit);
            }
            // 3.成功，根据id查询
            r = queryFunction.apply(id);
            // 模拟重建延迟
            Thread.sleep(200);
            if (r == null) {
                // 不存在，返回错误
                // 防止缓存穿透，缓存空对象
                this.set(key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
                return null;
            }
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        } finally {
            // 释放互斥锁
            unlock(lockId);
        }
        // 返回
        this.set(key, r, time, unit);
        return r;
    }

    /**
     * 逻辑过期解决缓存击穿问题
     *
     * @param id            对象id
     * @param type          对象类型
     * @param queryFunction 查询函数
     * @param time          时间
     * @param unit          时间单位
     * @return 缓存信息
     */
    public <R, ID> R queryWithLogicalExpire(String keyPrefix, ID id, Class<R> type, Function<ID, R> queryFunction, Long time, TimeUnit unit) {
        // 先从Redis查询，如果存在，则返回，否则从数据库中查询并缓存到Redis中
        String key = keyPrefix + id;
        String json = stringRedisTemplate.opsForValue().get(key);
        if (StrUtil.isBlank(json)) {
            // 缓存不存在，不是热点数据，直接返回
            return null;
        }
        // 命中，需要判断是否逻辑过期
        // 转为RedisData对象
        RedisData redisData = JSONUtil.toBean(json, RedisData.class);
        // 获取数据
        R r = JSONUtil.toBean(JSONUtil.toJsonStr(redisData.getData()), type);
        // 判断逻辑过期时间
        LocalDateTime expireTime = redisData.getExpireTime();
        if (expireTime.isAfter(LocalDateTime.now())) {
            // 未过期，直接返回
            return r;
        }
        // 已过期，需要缓存重建
        // 获取锁
        String lockId = LOCK_SHOP_KEY + id;
        boolean isLock = tryLock(lockId);
        if (isLock) {
            // 获取锁成功，开启独立线程重建缓存
            CACHE_REBUILD_EXECUTOR.submit(() -> {
                try {
                    // 重建缓存
                    // 获取锁成功，根据id查询
                    R newR = queryFunction.apply(id);
                    // 模拟重建延迟
                    Thread.sleep(200);
                    // 写入Redis
                    this.setWithLogicalExpire(key, newR, time, unit);
                    log.debug("重建缓存成功");
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    // 释放锁
                    unlock(lockId);
                }
            });
        }
        // 返回过期的商品数据
        return r;
    }

    /**
     * 尝试获取锁
     *
     * @param key 锁的key
     * @return true表示获取锁成功, false表示获取锁失败
     */
    public boolean tryLock(String key) {
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 5L, TimeUnit.SECONDS);
        return flag != null && flag; // 返回true表示获取锁成功, false和null（转为false）表示获取锁失败
    }

    /**
     * 释放锁
     *
     * @param lockId 锁的key
     */
    private void unlock(String lockId) {
        stringRedisTemplate.delete(lockId);
    }
}

package com.hmdp.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.hmdp.utils.CacheClient;
import com.hmdp.utils.RedisData;
import com.hmdp.utils.SystemConstants;
import org.springframework.data.geo.Distance;
import org.springframework.data.geo.GeoResult;
import org.springframework.data.geo.GeoResults;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.domain.geo.GeoReference;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private CacheClient cacheClient;


    /**
     * 根据id查询商铺信息
     *
     * @param id 商铺id
     * @return 商铺详情数据
     */
    @Override
//    @Cacheable(cacheNames = "cache:shop", key = "#id")
    public Result queryById(Long id) {
        // 解决缓存穿透问题使用缓存空对象
        Shop shop = cacheClient.queryWithPassThrough(CACHE_SHOP_KEY, id, Shop.class, this::getById, CACHE_SHOP_TTL, TimeUnit.MINUTES);
        // 使用互斥锁解决缓存击穿问题
//        Shop shop = cacheClient.queryWithMutex(CACHE_SHOP_KEY, id, Shop.class, this::getById, CACHE_SHOP_TTL, TimeUnit.MINUTES);
        // 使用逻辑过期解决缓存击穿问题
//        Shop shop = cacheClient.queryWithLogicalExpire(CACHE_SHOP_KEY, id, Shop.class, this::getById, CACHE_SHOP_TTL, TimeUnit.MINUTES);
        if (shop == null) {
            return Result.fail("店铺不存在！");
        }
        // 返回
        return Result.ok(shop);

    }

    @Override
    @Transactional
    public Result updateShopById(Shop shop) {
        Long shopId = shop.getId();
        if (shopId == null) {
            return Result.fail("店铺id不能为空！");
        }
        // 先更新数据库，再更新Redis
        boolean update = updateById(shop);
        if (!update) {
            return Result.fail("更新店铺信息失败！");
        }
        // 删除Redis缓存
        stringRedisTemplate.delete(CACHE_SHOP_KEY + shopId);
        // 更新Redis缓存
        stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + shopId, JSONUtil.toJsonStr(shop), CACHE_SHOP_TTL, TimeUnit.MINUTES);
        return Result.ok();
    }

    /**
     * 新增商铺信息
     *
     * @param shop 商铺数据
     * @return 商铺id
     */
    @Override
    @Transactional
    public Result saveShop(Shop shop) {
        // 写入数据库
        boolean save = save(shop);
        if (!save) {
            return Result.fail("新增商铺信息失败！");
        }
        // 更新Redis缓存
        stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + shop.getId(), JSONUtil.toJsonStr(shop), CACHE_SHOP_TTL, TimeUnit.MINUTES);
        // 返回店铺id
        return Result.ok(shop.getId());
    }

    @Override
    public Result queryShopByType(Integer typeId, Integer current, Double x, Double y) {
        // 1.判断是否需要根据坐标查询
        if (x == null || y == null){
            // 根据类型分页查询
            Page<Shop> page = query()
                    .eq("type_id", typeId)
                    .page(new Page<>(current, SystemConstants.DEFAULT_PAGE_SIZE));
            // 返回数据
            return Result.ok(page.getRecords());
        }
        // 2.计算分页参数
        int from = (current - 1) * SystemConstants.DEFAULT_PAGE_SIZE;
        int end = current * SystemConstants.DEFAULT_PAGE_SIZE; // from + 分页大小
        // 3.查询redis、按照距离排序、分页。结果：shopId、distance
        String key = SHOP_GEO_KEY + typeId;
        GeoResults<RedisGeoCommands.GeoLocation<String>> results = stringRedisTemplate.opsForGeo().search(
                key,
                GeoReference.fromCoordinate(x, y),
                new Distance(5000), // 5km,
                RedisGeoCommands.GeoSearchCommandArgs.newGeoSearchArgs().includeDistance().includeCoordinates().limit(end)
        );
        // 4. 解析出id
        if (results == null) {
            return Result.ok(Collections.emptyList());
        }
        List<GeoResult<RedisGeoCommands.GeoLocation<String>>> list = results.getContent();
        if (list.size() <= from) {
            // 没有下一页数据
            return Result.ok(Collections.emptyList());
        }
        // 4.1. 截取from ~ end的部分
        list.stream().skip(from);
        List<Long> ids = new ArrayList<>(list.size());
        Map<String, Distance> resultMap = new HashMap<>(list.size());
        for (GeoResult<RedisGeoCommands.GeoLocation<String>> result : list) {
            // 4.2.获取店铺id
            String shopIdStr = result.getContent().getName();
            ids.add(Long.valueOf(shopIdStr));
            // 4.3.获取距离
            Distance distance = result.getDistance();
            resultMap.put(shopIdStr, distance);
        }
        String idsStr = StrUtil.join(",", ids);
        // 5.根据id查询Shop
        List<Shop> shops = query().in("id", ids).last("ORDER BY FIELD(id," + idsStr + ")").list();
        for (Shop shop : shops) {
            shop.setDistance(resultMap.get(shop.getId().toString()).getValue());
        }
        // 6.返回数据
        return Result.ok(shops);
    }

//    /**
//     * 使用缓存重建解决缓存击穿问题
//     *
//     * @param id 店铺id
//     * @return 店铺信息
//     */
//    private Shop queryWithPassThrough(Long id) {
//        // 先从Redis查询，如果存在，则返回，否则从数据库中查询并缓存到Redis中
//        String key = CACHE_SHOP_KEY + id;
//        String shopJson = stringRedisTemplate.opsForValue().get(key);
//        if (StrUtil.isNotBlank(shopJson)) {
//            // 存在，则返回
//            Shop shop = JSONUtil.toBean(shopJson, Shop.class);
//            return shop;
//        }
//        if (shopJson != null) {
//            // 用于处理空对象，避免重复查询数据库
//            return null;
//        }
//        Shop shop = getById(id);
//        if (shop == null) {
//            // 不存在，返回错误
//            // 防止缓存穿透，缓存空对象
//            stringRedisTemplate.opsForValue().set(key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
//            return null;
//        }
//        // 存在，则返回
//        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(shop), CACHE_SHOP_TTL, TimeUnit.MINUTES);
//        return shop;
//    }
//
//
//    /**
//     * 使用互斥锁解决缓存穿透问题
//     *
//     * @param id 店铺id
//     * @return 店铺信息
//     */
//    private Shop queryWithMutex(Long id) {
//        // 先从Redis查询，如果存在，则返回，否则从数据库中查询并缓存到Redis中
//        String key = CACHE_SHOP_KEY + id;
//        String shopJson = stringRedisTemplate.opsForValue().get(key);
//        if (StrUtil.isNotBlank(shopJson)) {
//            // 存在，则返回
//            Shop shop = JSONUtil.toBean(shopJson, Shop.class);
//            return shop;
//        }
//        if (shopJson != null) {
//            // 用于处理空对象，避免重复查询数据库
//            return null;
//        }
//        // 实现缓存重建
//        // 1.获取锁
//        String lockId = LOCK_SHOP_KEY + id;
//        Shop shop = null;
//        try {
//            boolean isLock = tryLock(lockId);
//            // 2.判断锁是否已存在
//            if (!isLock) {
//                // 获取锁失败，则休眠并重试
//                Thread.sleep(50);
//                return queryWithMutex(id);
//            }
//            // 3.成功，根据id查询
//            shop = getById(id);
//            // 模拟重建延迟
//            Thread.sleep(200);
//            if (shop == null) {
//                // 不存在，返回错误
//                // 防止缓存穿透，缓存空对象
//                stringRedisTemplate.opsForValue().set(key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
//                return null;
//            }
//        } catch (InterruptedException e) {
//            throw new RuntimeException(e);
//        } finally {
//            // 释放互斥锁
//            unlock(lockId);
//        }
//        // 返回
//        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(shop), CACHE_SHOP_TTL, TimeUnit.MINUTES);
//        return shop;
//    }
//
//    /**
//     * 使用逻辑过期解决缓存击穿问题
//     *
//     * @param id 店铺id
//     * @return 店铺信息
//     */
//    private Shop queryWithLogicalExpire(Long id) {
//        // 先从Redis查询，如果存在，则返回，否则从数据库中查询并缓存到Redis中
//        String key = CACHE_SHOP_KEY + id;
//        String shopJson = stringRedisTemplate.opsForValue().get(key);
//        if (StrUtil.isBlank(shopJson)) {
//            // 缓存不存在，不是热点数据，直接返回
//            return null;
//        }
//        // 命中，需要判断是否逻辑过期
//        // 转为RedisData对象
//        RedisData redisData = JSONUtil.toBean(shopJson, RedisData.class);
//        // 获取数据
//        Shop shop = JSONUtil.toBean((String) redisData.getData(), Shop.class);
//        // 判断逻辑过期时间
//        LocalDateTime expireTime = redisData.getExpireTime();
//        if (expireTime.isAfter(LocalDateTime.now())) {
//            // 未过期，直接返回
//            return shop;
//        }
//        // 已过期，需要缓存重建
//        // 获取锁
//        String lockId = LOCK_SHOP_KEY + id;
//        boolean isLock = tryLock(lockId);
//        if (isLock) {
//            // 获取锁成功，开启独立线程重建缓存
//            CACHE_REBUILD_EXECUTOR.submit(() -> {
//                try {
//                    // 重建缓存
//                    this.saveShopToRedis(id);
//                } catch (Exception e) {
//                    throw new RuntimeException(e);
//                } finally {
//                    // 释放锁
//                    unlock(lockId);
//                }
//            });
//        }
//        // 返回过期的商品数据
//        return shop;
//    }

}

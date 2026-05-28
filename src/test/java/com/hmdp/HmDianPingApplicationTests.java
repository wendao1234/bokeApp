package com.hmdp;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.lang.UUID;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Shop;
import com.hmdp.service.impl.ShopServiceImpl;
import com.hmdp.utils.RedisData;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.geo.Point;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.GeoOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static com.hmdp.utils.RedisConstants.*;

@SpringBootTest
class HmDianPingApplicationTests {
    @Resource
    private ShopServiceImpl shopServiceImpl;
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Test
    void contextLoads() throws InterruptedException {
        Long id = 1L;
        Shop shop = shopServiceImpl.getById(id);
        // 模拟重建延迟
        Thread.sleep(200);
        // 封装逻辑过期时间
        RedisData redisData = new RedisData();
        redisData.setData(shop);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(LOCK_SHOP_TTL));
        // 写入Redis
        stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id, JSONUtil.toJsonStr(redisData));
    }

    @Test
    void testCreateToken() {
        for (int i = 0; i < 1000; i++) {
            String token = UUID.randomUUID().toString(true);
            String tokenKey = LOGIN_USER_KEY + token;
            UserDTO userDTO = new UserDTO();
            userDTO.setId((long) (1001 + i));
            Map<String, Object> userMap = BeanUtil.beanToMap(userDTO, new HashMap<>(),
                    CopyOptions.create()
                            .setIgnoreNullValue(true)
                            .setFieldValueEditor((fieldName, fieldValue) -> fieldValue == null ? "" : fieldValue.toString()));
            stringRedisTemplate.opsForHash().putAll(tokenKey, userMap);
            System.out.println(token);
        }
    }

    @Test
    void testSaveShopToRedis() {
        List<Shop> list = shopServiceImpl.list();
        // 分组，店铺类型作为key
        Map<String, List<Shop>> map = list.stream().collect(Collectors.groupingBy(shop -> shop.getTypeId().toString()));
        // 保存到Redis中，以GEO形式存储
        for (Map.Entry<String, List<Shop>> entry : map.entrySet()) {
            String key = SHOP_GEO_KEY + entry.getKey();
            List<Shop> value = entry.getValue();
            List<RedisGeoCommands.GeoLocation<String>> geoLocations = new ArrayList<>(value.size());
            for (Shop shop : value) {
                // 店铺经纬度
                geoLocations.add(new RedisGeoCommands.GeoLocation<>(
                        shop.getId().toString(),
                        new Point(shop.getX(), shop.getY())
                ));
            }
            stringRedisTemplate.opsForGeo().add(key, geoLocations);
        }
    }
}

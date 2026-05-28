package com.hmdp.service.impl;

import cn.hutool.core.collection.CollUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.hmdp.entity.Blog;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.BlogMapper;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IAiRecommendService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static com.hmdp.utils.RedisConstants.*;

/**
 * AI推荐服务实现类
 * 基于协同过滤算法（Collaborative Filtering）
 *
 * 优化说明：
 * 1. 使用反向索引替代keys()全量扫描，避免Redis阻塞
 * 2. 使用余弦相似度替代简单的交集计数，提高推荐准确度
 * 3. 批量预计算用户相似度并缓存，避免每次请求重复计算
 * 4. 推荐结果缓存（TTL 10分钟），避免重复计算
 * 5. 活跃用户集合缓存，高效获取候选用户
 */
@Slf4j
@Service
public class AiRecommendServiceImpl implements IAiRecommendService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private BlogMapper blogMapper;

    @Resource
    private ShopMapper shopMapper;

    private static final double VIEW_WEIGHT = 1.0;
    private static final double LIKE_WEIGHT = 3.0;

    // ========================= 推荐入口 =========================

    @Override
    public List<Blog> recommendBlogs(Long userId, int limit) {
        // 1. 检查推荐缓存
        String cacheKey = AI_RECOMMEND_CACHE_KEY + "blog:" + userId;
        List<String> cachedIds = stringRedisTemplate.opsForList().range(cacheKey, 0, -1);
        if (CollUtil.isNotEmpty(cachedIds)) {
            List<Long> ids = cachedIds.stream().map(Long::valueOf).collect(Collectors.toList());
            List<Blog> blogs = blogMapper.selectBatchIds(ids);
            if (CollUtil.isNotEmpty(blogs)) {
                return blogs;
            }
        }

        try {
            // 2. 获取用户行为数据
            Set<String> userBehavior = getUserBlogBehavior(userId);
            if (CollUtil.isEmpty(userBehavior)) {
                List<Blog> hot = getHotBlogs(limit);
                cacheRecommendation(cacheKey, hot.stream().map(Blog::getId).collect(Collectors.toList()));
                return hot;
            }

            // 3. 获取候选用户并计算相似度
            List<SimilarUser> similarUsers = computeSimilarUsers(userId);
            if (CollUtil.isEmpty(similarUsers)) {
                List<Blog> hot = getHotBlogs(limit);
                cacheRecommendation(cacheKey, hot.stream().map(Blog::getId).collect(Collectors.toList()));
                return hot;
            }

            // 4. 收集推荐候选（排除已浏览），按加权得分排序
            List<Long> recommendedIds = collectRecommendations(
                    userId, similarUsers, userBehavior, limit, this::getBlogSimilarUserItems);

            if (CollUtil.isEmpty(recommendedIds)) {
                List<Blog> hot = getHotBlogs(limit);
                cacheRecommendation(cacheKey, hot.stream().map(Blog::getId).collect(Collectors.toList()));
                return hot;
            }

            // 5. 缓存并返回
            cacheRecommendation(cacheKey, recommendedIds);
            return blogMapper.selectBatchIds(recommendedIds);

        } catch (Exception e) {
            log.error("推荐博客失败", e);
            return getHotBlogs(limit);
        }
    }

    @Override
    public List<Shop> recommendShops(Long userId, int limit) {
        // 1. 检查推荐缓存
        String cacheKey = AI_RECOMMEND_CACHE_KEY + "shop:" + userId;
        List<String> cachedIds = stringRedisTemplate.opsForList().range(cacheKey, 0, -1);
        if (CollUtil.isNotEmpty(cachedIds)) {
            List<Long> ids = cachedIds.stream().map(Long::valueOf).collect(Collectors.toList());
            List<Shop> shops = shopMapper.selectBatchIds(ids);
            if (CollUtil.isNotEmpty(shops)) {
                return shops;
            }
        }

        try {
            // 2. 获取用户行为数据
            Set<String> userBehavior = getUserShopBehavior(userId);
            if (CollUtil.isEmpty(userBehavior)) {
                List<Shop> hot = getHotShops(limit);
                cacheRecommendation(cacheKey, hot.stream().map(Shop::getId).collect(Collectors.toList()));
                return hot;
            }

            // 3. 获取候选用户并计算相似度
            List<SimilarUser> similarUsers = computeSimilarUsers(userId);
            if (CollUtil.isEmpty(similarUsers)) {
                List<Shop> hot = getHotShops(limit);
                cacheRecommendation(cacheKey, hot.stream().map(Shop::getId).collect(Collectors.toList()));
                return hot;
            }

            // 4. 收集推荐候选
            List<Long> recommendedIds = collectRecommendations(
                    userId, similarUsers, userBehavior, limit, this::getShopSimilarUserItems);

            if (CollUtil.isEmpty(recommendedIds)) {
                List<Shop> hot = getHotShops(limit);
                cacheRecommendation(cacheKey, hot.stream().map(Shop::getId).collect(Collectors.toList()));
                return hot;
            }

            // 5. 缓存并返回
            cacheRecommendation(cacheKey, recommendedIds);
            return shopMapper.selectBatchIds(recommendedIds);

        } catch (Exception e) {
            log.error("推荐商铺失败", e);
            return getHotShops(limit);
        }
    }

    // ========================= 行为记录（维护反向索引）=========================

    @Override
    public void recordBlogView(Long userId, Long blogId) {
        // 正向索引：用户 -> 浏览过的博客
        String userViewKey = USER_BLOG_VIEW_KEY + userId;
        stringRedisTemplate.opsForZSet().add(userViewKey, blogId.toString(), System.currentTimeMillis());

        // 反向索引：博客 -> 浏览过该博客的用户集合（替代keys()扫描）
        stringRedisTemplate.opsForSet().add(BLOG_VIEWERS_KEY + blogId, userId.toString());

        // 记录活跃用户（用于相似度计算的候选集）
        stringRedisTemplate.opsForSet().add(USER_BEHAVIOR_USERS_KEY, userId.toString());

        // 清除该用户的推荐缓存
        stringRedisTemplate.delete(AI_RECOMMEND_CACHE_KEY + "blog:" + userId);
    }

    @Override
    public void recordShopView(Long userId, Long shopId) {
        // 正向索引
        String userViewKey = USER_SHOP_VIEW_KEY + userId;
        stringRedisTemplate.opsForZSet().add(userViewKey, shopId.toString(), System.currentTimeMillis());

        // 反向索引
        stringRedisTemplate.opsForSet().add(SHOP_VIEWERS_KEY + shopId, userId.toString());

        // 记录活跃用户
        stringRedisTemplate.opsForSet().add(USER_BEHAVIOR_USERS_KEY, userId.toString());

        // 清除该用户的推荐缓存
        stringRedisTemplate.delete(AI_RECOMMEND_CACHE_KEY + "shop:" + userId);
    }

    // ========================= 相似度计算（批量预计算+缓存）=========================

    /**
     * 获取相似用户列表（优先从缓存读取，未命中则批量计算）
     */
    private List<SimilarUser> computeSimilarUsers(Long userId) {
        // 1. 检查相似度缓存
        String simKey = AI_SIMILARITY_KEY + userId;
        Set<org.springframework.data.redis.core.ZSetOperations.TypedTuple<String>> cached =
                stringRedisTemplate.opsForZSet().reverseRangeWithScores(simKey, 0, 19);
        if (CollUtil.isNotEmpty(cached)) {
            List<SimilarUser> result = new ArrayList<>();
            for (org.springframework.data.redis.core.ZSetOperations.TypedTuple<String> tuple : cached) {
                Double score = tuple.getScore();
                if (score != null && score > 0) {
                    result.add(new SimilarUser(Long.valueOf(tuple.getValue()), score));
                }
            }
            return result;
        }

        // 2. 缓存未命中，批量计算
        Set<String> userBehavior = getUserBlogBehavior(userId);
        if (CollUtil.isEmpty(userBehavior)) {
            return Collections.emptyList();
        }

        // 获取所有有行为记录的用户（通过反向索引，避免keys()扫描）
        Set<String> allUserIds = stringRedisTemplate.opsForSet().members(USER_BEHAVIOR_USERS_KEY);
        if (CollUtil.isEmpty(allUserIds)) {
            return Collections.emptyList();
        }

        List<SimilarUser> similarUsers = new ArrayList<>();
        for (String otherUserIdStr : allUserIds) {
            Long otherUserId = Long.valueOf(otherUserIdStr);
            if (otherUserId.equals(userId)) {
                continue;
            }
            double similarity = cosineSimilarity(userId, otherUserId);
            if (similarity > 0) {
                similarUsers.add(new SimilarUser(otherUserId, similarity));
            }
        }

        // 3. 按相似度排序，取Top20
        similarUsers.sort((a, b) -> Double.compare(b.similarity, a.similarity));
        if (similarUsers.size() > 20) {
            similarUsers = similarUsers.subList(0, 20);
        }

        // 4. 写入缓存
        for (SimilarUser su : similarUsers) {
            stringRedisTemplate.opsForZSet().add(simKey, su.userId.toString(), su.similarity);
        }
        stringRedisTemplate.expire(simKey, AI_SIMILARITY_TTL, TimeUnit.MINUTES);

        return similarUsers;
    }

    /**
     * 计算两个用户之间的余弦相似度
     * cos(A,B) = (A·B) / (|A| * |B|)
     * 将用户行为向量化：每个item是一个维度，有行为则权重>0
     */
    private double cosineSimilarity(Long userId1, Long userId2) {
        Set<String> behavior1 = getUserBlogBehavior(userId1);
        Set<String> behavior2 = getUserBlogBehavior(userId2);

        if (behavior1.isEmpty() || behavior2.isEmpty()) {
            return 0.0;
        }

        // 计算交集大小（点积，假设所有行为权重相同）
        int dotProduct = 0;
        for (String item : behavior1) {
            if (behavior2.contains(item)) {
                dotProduct++;
            }
        }

        if (dotProduct == 0) {
            return 0.0;
        }

        // 余弦相似度 = 交集 / sqrt(|A| * |B|)
        double magnitude = Math.sqrt((double) behavior1.size() * behavior2.size());
        return dotProduct / magnitude;
    }

    // ========================= 行为数据获取 =========================

    /**
     * 获取用户博客行为（浏览 + 点赞），通过反向索引获取点赞数据
     */
    private Set<String> getUserBlogBehavior(Long userId) {
        Set<String> behavior = new HashSet<>();

        // 浏览记录
        String viewKey = USER_BLOG_VIEW_KEY + userId;
        Set<String> views = stringRedisTemplate.opsForZSet().range(viewKey, 0, -1);
        if (CollUtil.isNotEmpty(views)) {
            behavior.addAll(views);
        }

        // 点赞记录（通过反向索引，替代keys()全量扫描）
        String likedKey = USER_LIKED_BLOGS_KEY + userId;
        Set<String> likedBlogs = stringRedisTemplate.opsForSet().members(likedKey);
        if (CollUtil.isNotEmpty(likedBlogs)) {
            behavior.addAll(likedBlogs);
        }

        return behavior;
    }

    private Set<String> getUserShopBehavior(Long userId) {
        String userViewKey = USER_SHOP_VIEW_KEY + userId;
        Set<String> views = stringRedisTemplate.opsForZSet().range(userViewKey, 0, -1);
        return views != null ? views : Collections.emptySet();
    }

    // ========================= 推荐候选收集 =========================

    private Set<String> getSimilarUserItems(Long similarUserId) {
        Set<String> items = new HashSet<>();
        Set<String> views = stringRedisTemplate.opsForZSet().range(USER_BLOG_VIEW_KEY + similarUserId, 0, -1);
        if (CollUtil.isNotEmpty(views)) {
            items.addAll(views);
        }
        Set<String> liked = stringRedisTemplate.opsForSet().members(USER_LIKED_BLOGS_KEY + similarUserId);
        if (CollUtil.isNotEmpty(liked)) {
            items.addAll(liked);
        }
        return items;
    }

    private List<Long> collectRecommendations(
            Long userId,
            List<SimilarUser> similarUsers,
            Set<String> userBehavior,
            int limit,
            SimilarUserItemProvider itemProvider) {

        Map<Long, Double> scores = new HashMap<>();

        for (SimilarUser su : similarUsers) {
            Set<String> similarUserItems = itemProvider.getItems(su.userId);
            for (String itemIdStr : similarUserItems) {
                if (!userBehavior.contains(itemIdStr)) {
                    Long itemId = Long.valueOf(itemIdStr);
                    // 加权得分 = 相似度 * 1.0
                    scores.put(itemId, scores.getOrDefault(itemId, 0.0) + su.similarity);
                }
            }
        }

        return scores.entrySet().stream()
                .sorted(Map.Entry.<Long, Double>comparingByValue().reversed())
                .limit(limit)
                .map(Map.Entry::getKey)
                .collect(Collectors.toList());
    }

    @FunctionalInterface
    private interface SimilarUserItemProvider {
        Set<String> getItems(Long userId);
    }

    private Set<String> getBlogSimilarUserItems(Long userId) {
        return getSimilarUserItems(userId);
    }

    private Set<String> getShopSimilarUserItems(Long userId) {
        String viewKey = USER_SHOP_VIEW_KEY + userId;
        Set<String> views = stringRedisTemplate.opsForZSet().range(viewKey, 0, -1);
        return views != null ? views : Collections.emptySet();
    }

    // ========================= 兜底策略 =========================

    private List<Blog> getHotBlogs(int limit) {
        QueryWrapper<Blog> queryWrapper = new QueryWrapper<>();
        queryWrapper.orderByDesc("liked").last("LIMIT " + limit);
        return blogMapper.selectList(queryWrapper);
    }

    private List<Shop> getHotShops(int limit) {
        QueryWrapper<Shop> queryWrapper = new QueryWrapper<>();
        queryWrapper.orderByDesc("sold").last("LIMIT " + limit);
        return shopMapper.selectList(queryWrapper);
    }

    // ========================= 缓存工具 =========================

    private void cacheRecommendation(String cacheKey, List<Long> ids) {
        if (CollUtil.isEmpty(ids)) {
            return;
        }
        List<String> idStrs = ids.stream().map(String::valueOf).collect(Collectors.toList());
        stringRedisTemplate.opsForList().rightPushAll(cacheKey, idStrs);
        stringRedisTemplate.expire(cacheKey, AI_RECOMMEND_CACHE_TTL, TimeUnit.MINUTES);
    }

    // ========================= 内部数据结构 =========================

    private static class SimilarUser {
        Long userId;
        double similarity;

        SimilarUser(Long userId, double similarity) {
            this.userId = userId;
            this.similarity = similarity;
        }
    }
}
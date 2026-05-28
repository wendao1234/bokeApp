package com.hmdp.service;

import com.hmdp.entity.Blog;
import com.hmdp.entity.Shop;

import java.util.List;

/**
 * AI推荐服务接口
 * 基于协同过滤算法实现个性化推荐
 */
public interface IAiRecommendService {

    /**
     * 推荐博客（基于用户行为的协同过滤）
     * @param userId 用户ID
     * @param limit 推荐数量
     * @return 推荐的博客列表
     */
    List<Blog> recommendBlogs(Long userId, int limit);

    /**
     * 推荐商铺（基于用户行为的协同过滤）
     * @param userId 用户ID
     * @param limit 推荐数量
     * @return 推荐的商铺列表
     */
    List<Shop> recommendShops(Long userId, int limit);

    /**
     * 记录用户浏览行为
     * @param userId 用户ID
     * @param blogId 博客ID
     */
    void recordBlogView(Long userId, Long blogId);

    /**
     * 记录用户浏览商铺行为
     * @param userId 用户ID
     * @param shopId 商铺ID
     */
    void recordShopView(Long userId, Long shopId);
}

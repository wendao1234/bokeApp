package com.hmdp.utils;

import java.util.concurrent.TimeUnit;

public class RedisConstants {
    public static final String LOGIN_CODE_KEY = "login:code:";
    public static final Long LOGIN_CODE_TTL = 2L;
    public static final String LOGIN_USER_KEY = "login:token:";
    public static final Long LOGIN_USER_TTL = 36L;

    public static final Long CACHE_NULL_TTL = 1L;

    public static final Long CACHE_SHOP_TTL = 1L;
    public static final String CACHE_SHOP_KEY = "cache:shop:";
    public static final String CACHE_SHOP_TYPE_KEY = "cache:shop:type";
    public static final Long CACHE_SHOP_TYPE_TTL = 1L;

    public static final String LOCK_SHOP_KEY = "lock:shop:";
    public static final Long LOCK_SHOP_TTL = 1L;

    public static final String SECKILL_STOCK_KEY = "seckill:stock:";
    public static final String BLOG_LIKED_KEY = "blog:liked:";
    public static final String FEED_KEY = "feed:";
    public static final String SHOP_GEO_KEY = "shop:geo:";
    public static final String USER_SIGN_KEY = "sign:";

    // AI推荐相关 - 反向索引keys
    public static final String USER_LIKED_BLOGS_KEY = "ai:user:liked:blogs:";
    public static final String BLOG_VIEWERS_KEY = "ai:blog:viewers:";
    public static final String SHOP_VIEWERS_KEY = "ai:shop:viewers:";
    public static final String USER_BEHAVIOR_USERS_KEY = "ai:behavior:users";

    // AI推荐相关 - 缓存keys
    public static final String AI_RECOMMEND_CACHE_KEY = "ai:recommend:cache:";
    public static final Long AI_RECOMMEND_CACHE_TTL = 10L;
    public static final String AI_SIMILARITY_KEY = "ai:similarity:";
    public static final Long AI_SIMILARITY_TTL = 30L;
}
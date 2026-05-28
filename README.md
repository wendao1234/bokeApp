# bokeApp

## 项目概述

基于 SpringBoot 的分布式博客App后端服务（学习项目）。支持短信登录、商铺缓存、博客发布、优惠券秒杀、好友关注、附近商铺并集成AI等功能。

## 技术栈

- Java 8 + Spring Boot 2.3.12
- MyBatis-Plus 3.4.3
- Redis (Lettuce 6.1.6) + Redisson 3.13.6
- MySQL 5.x (mysql-connector 5.1.47)
- Hutool 5.7.17
- Lombok

## 项目结构

```
src/main/java/com/boke/
├── config/          # MvcConfig, RedissonConfig, MybatisConfig, WebExceptionAdvice
├── controller/      # REST 控制器（/user, /shop, /blog, /voucher-order, /follow 等）
├── dto/             # LoginFormDTO, Result, ScrollResult, UserDTO
├── entity/          # 数据库实体类
├── interceptor/     # RefreshTokenInterceptor(order=0), LoginInterceptor(order=1)
├── mapper/          # MyBatis-Plus Mapper 接口
├── service/         # 业务接口
│   └── impl/        # 业务实现
└── utils/           # 工具类（CacheClient, RedisIdWorker, RedisConstants 等）
```

## 核心模块说明

### 登录认证
- 基于 Redis Hash 存储用户会话（key: `login:token:{uuid}`，TTL 36min）
- RefreshTokenInterceptor 每次请求刷新 token 过期时间
- LoginInterceptor 拦截需要登录的接口，未登录返回 401
- 公开接口：`/shop/**`, `/blog/hot`, `/user/code`, `/user/login`, `/voucher/**`

### 缓存策略（CacheClient 工具类）
- **缓存穿透**：缓存空对象（空字符串）
- **缓存击穿**：互斥锁（SETNX）或逻辑过期 + 异步重建
- **缓存雪崩**：随机过期时间

### 秒杀下单
- Lua 脚本（`seckill.lua`）原子执行：库存判断 + 一人一单校验 + 扣减库存 + 写入 Stream
- Redis Stream（`stream.orders`）异步消费，落库由 VoucherOrderService 处理
- Redisson 分布式锁保证一人一单

### 社交功能
- 点赞：ZSet（`blog:liked:{blogId}`），score 为时间戳，支持 Top-N 查询
- 关注：Set（`follow:{userId}`），SINTER 计算共同关注
- Feed 推送：写扩散模型，博客发布时 push 到粉丝 ZSet（`feed:{userId}`）

### 附近商铺
- Redis GEO（`shop:geo:{typeId}`），按距离排序，5km 范围查询

### 全局唯一 ID（RedisIdWorker）
- 结构：1bit符号位 + 31bit时间戳（秒，基于2022-01-01） + 32bit序列号（Redis INCR 按天分 key）

### AI 功能模块

#### 1. 智能内容审核（AiContentModerationService）
- **功能**：博客发布时自动审核内容，防止违规信息
- **实现**：基于敏感词库 + 规则引擎（可扩展为第三方 AI API）
- **审核规则**：
  - 敏感词检测（政治、色情、暴力等）
  - 广告检测（多个广告关键词组合）
  - 联系方式检测（手机号、微信号）
  - 外链检测（URL 过滤）
  - 内容质量检测（长度、重复字符）
- **接口**：`POST /blog` 发布博客时自动调用审核

#### 2. 智能推荐系统（AiRecommendService）
- **算法**：基于用户行为的协同过滤（Collaborative Filtering）
- **数据来源**：
  - 用户浏览记录（Redis ZSet 存储，保留最近 100 条）
  - 用户点赞记录（复用现有点赞数据）
- **推荐流程**：
  1. 收集用户行为数据（浏览 + 点赞）
  2. 计算用户相似度（基于共同行为）
  3. 推荐相似用户喜欢的内容
  4. 兜底策略：无行为数据时返回热门内容
- **接口**：
  - `GET /blog/recommend?limit=10` - 推荐博客
  - `GET /shop/recommend?limit=10` - 推荐商铺
- **行为记录**：
  - 查看博客详情时自动记录（`GET /blog/{id}`）
  - 查看商铺详情时自动记录（`GET /shop/{id}`）

## Redis Key 规范

| Key 模式 | 类型 | 用途 |
|---|---|---|
| `login:code:{phone}` | String | 验证码，TTL 2min |
| `login:token:{token}` | Hash | 用户会话，TTL 36min |
| `cache:shop:{id}` | String(JSON) | 商铺缓存 |
| `cache:shop:type` | String | 商铺类型列表缓存 |
| `lock:shop:{id}` | String | 缓存重建互斥锁 |
| `seckill:stock:{voucherId}` | String | 秒杀库存 |
| `blog:liked:{blogId}` | ZSet | 点赞用户集合 |
| `feed:{userId}` | ZSet | 用户收件箱（Feed） |
| `shop:geo:{typeId}` | GEO | 商铺地理位置 |
| `sign:{userId}:{yyyyMM}` | Bitmap | 签到记录 |
| `icr:{biz}:{yyyy:MM:dd}` | String | ID 生成器计数器 |
| `recommend:user:blog:view:{userId}` | ZSet | 用户博客浏览记录（AI 推荐） |
| `recommend:user:shop:view:{userId}` | ZSet | 用户商铺浏览记录（AI 推荐） |

## Lua 脚本

- `seckill.lua`：秒杀资格校验 + 下单消息投递（原子操作）
- `unlock.lua`：分布式锁释放（check-and-delete）


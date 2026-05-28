
package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.dto.ScrollResult;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Blog;
import com.hmdp.entity.Follow;
import com.hmdp.entity.User;
import com.hmdp.mapper.BlogMapper;
import com.hmdp.service.IBlogService;
import com.hmdp.service.IFollowService;
import com.hmdp.service.IUserService;
import com.hmdp.service.IAiContentModerationService;
import com.hmdp.utils.SystemConstants;
import com.hmdp.utils.UserHolder;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static com.hmdp.utils.RedisConstants.BLOG_LIKED_KEY;
import static com.hmdp.utils.RedisConstants.FEED_KEY;
import static com.hmdp.utils.RedisConstants.USER_LIKED_BLOGS_KEY;
import static com.hmdp.utils.RedisConstants.BLOG_VIEWERS_KEY;
import static com.hmdp.utils.RedisConstants.USER_BEHAVIOR_USERS_KEY;
import static com.hmdp.utils.RedisConstants.AI_RECOMMEND_CACHE_KEY;

@Service
public class BlogServiceImpl extends ServiceImpl<BlogMapper, Blog> implements IBlogService {
    @Resource
    private IUserService userService;
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private IFollowService followService;
    @Resource
    private IAiContentModerationService aiContentModerationService;

    /**
     * 查询笔记详情
     *
     * @param id 笔记id
     * @return Result
     */
    @Override
    public Result queryBlogById(Long id) {
        // 根据id查询blog
        Blog blog = getById(id);
        if (blog == null) {
            return Result.fail("笔记不存在！");
        }
        // 查询blog有关的用户
        queryBlogUser(blog);
        // 是否点赞
        isBlogLiked(blog);
        return Result.ok(blog);
    }

    /**
     * 查询最热笔记
     *
     * @param current 当前页
     * @return Result
     */
    @Override
    public Result queryHotBlog(Integer current) {
        // 根据用户查询
        Page<Blog> page = query()
                .orderByDesc("liked")
                .page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
        // 获取当前页数据
        List<Blog> records = page.getRecords();
        records.forEach(blog -> {
            // 查询blog有关的用户
            this.queryBlogUser(blog);
            // 是否点赞
            this.isBlogLiked(blog);
        });
        return Result.ok(records);
    }

    /**
     * 点赞笔记功能
     *
     * @param id 笔记id
     * @return Result
     */
    @Override
    public Result likeBlog(Long id) {
        // 1.获取登录用户
        UserDTO user = UserHolder.getUser();
        if (user == null) {
            // 未登录 无需点赞
            return null;
        }
        Long userId = user.getId();
        // 2.判断当前登录用户是否已经点赞
        Double score = stringRedisTemplate.opsForZSet().score(BLOG_LIKED_KEY + id, userId.toString());
        // 3. 如果未点赞，可以点赞
        if (score == null) {
            // 3.1. 数据库点赞数 + 1
            boolean isSuccess = update().setSql("liked = liked + 1").eq("id", id).update();
            // 3.2.保存用户到Redis的set集合
            if (isSuccess) {
                stringRedisTemplate.opsForZSet().incrementScore(BLOG_LIKED_KEY + id, userId.toString(), System.currentTimeMillis());
                // 维护点赞反向索引（用于推荐系统）
                stringRedisTemplate.opsForSet().add(USER_LIKED_BLOGS_KEY + userId, id.toString());
                stringRedisTemplate.opsForSet().add(USER_BEHAVIOR_USERS_KEY, userId.toString());
            }
        } else {
            // 4. 如果已点赞，取消点赞
            // 4.1.数据库点赞数 -1
            boolean isSuccess = update().setSql("liked = liked - 1").eq("id", id).update();
            // 4.2.把用户从Redis的set集合移除
            if (isSuccess) {
                stringRedisTemplate.opsForZSet().remove(BLOG_LIKED_KEY + id, String.valueOf(userId));
                // 维护点赞反向索引
                stringRedisTemplate.opsForSet().remove(USER_LIKED_BLOGS_KEY + userId, id.toString());
            }
        }
        return Result.ok();
    }

    /**
     * 查询点赞过该笔记的用户
     *
     * @param id 笔记id
     * @return Result
     */
    @Override
    public Result queryBlogByLikes(Long id) {
        // 查询top5点赞用户
        String key = BLOG_LIKED_KEY + id;
        Set<String> top5UserId = stringRedisTemplate.opsForZSet().range(key, 0, 4);
        if (top5UserId == null || top5UserId.isEmpty()) {
            return Result.ok(Collections.emptyList());
        }
        // 解析出其中的用户id
        List<Long> ids = top5UserId.stream().map(Long::valueOf).collect(Collectors.toList());
        String strIds = StrUtil.join(",", ids);
        // 根据用户id查询用户
        List<UserDTO> users = userService.query().in("id", ids)
                .last("ORDER BY FIELD(id," + strIds+ ")").list()
                .stream().map(user -> BeanUtil.copyProperties(user, UserDTO.class)).collect(Collectors.toList());
        return Result.ok(users);
    }

    /**
     * 保存笔记
     *
     * @param blog 笔记
     * @return Result
     */
    @Override
    public Result saveBlog(Blog blog) {
        // 获取登录用户
        UserDTO user = UserHolder.getUser();
        blog.setUserId(user.getId());
        blog.setComments(0);

        // AI内容审核
        String contentToCheck = blog.getTitle() + " " + blog.getContent();
        IAiContentModerationService.ModerationResult moderationResult =
                aiContentModerationService.moderateTextWithDetail(contentToCheck);
        if (!moderationResult.isPass()) {
            return Result.fail(moderationResult.getReason());
        }

        // 保存探店博文
        save(blog);
        // 维护浏览反向索引（作者视为浏览了自己的博客）
        stringRedisTemplate.opsForSet().add(BLOG_VIEWERS_KEY + blog.getId(), user.getId().toString());
        stringRedisTemplate.opsForSet().add(USER_BEHAVIOR_USERS_KEY, user.getId().toString());
        stringRedisTemplate.delete(AI_RECOMMEND_CACHE_KEY + "blog:" + user.getId());
        // 查询笔记作者的所有粉丝
        List<Follow> follows = followService.query().eq("follow_user_id", user.getId()).list();
        if (follows == null || follows.isEmpty()) {
            return Result.ok(Collections.emptyList());
        }
        // 推送笔记
        follows.forEach(follow -> {
            // 获取粉丝id
            Long userId = follow.getUserId();
            // 给粉丝发送消息
            String key = FEED_KEY + userId;
            stringRedisTemplate.opsForZSet().add(key, blog.getId().toString(), System.currentTimeMillis());
        });
        // 返回id
        return Result.ok(blog.getId());
    }

    /**
     * 查询笔记关注用户
     *
     * @param max 上一次查询的最大id
     * @param offset 偏移量
     * @return Result
     */
    @Override
    public Result queryBlogOfFollow(Long max, Integer offset) {
        // 1.获取当前用户
        Long userId = UserHolder.getUser().getId();
        // 2.查询收件箱 ZREVRANGEBYSCORE key Max Min LIMIT offset count
        String key = FEED_KEY + userId;
        Set<ZSetOperations.TypedTuple<String>> typedTuples = stringRedisTemplate.opsForZSet()
                .reverseRangeByScoreWithScores(key, 0, max, offset, 2);
        // 3.非空判断
        if (typedTuples == null || typedTuples.isEmpty()) {
            return Result.ok();
        }
        // 4.解析数据：blogId、minTime（时间戳）、offset
        List<Long> ids = new ArrayList<>(typedTuples.size());
        long minTime = 0;
        int os = 1;
        for (ZSetOperations.TypedTuple<String> tuple : typedTuples) {
            // 4.1.获取笔记id
            ids.add(Long.valueOf(tuple.getValue()));
            // 4.2.获取分数(时间戳）
            long time = tuple.getScore().longValue();
            if (time == minTime) {
                os++;
            } else {
                minTime = time;
                os = 1;
            }
        }
        os = minTime == max ? os : os + offset;
        // 5.根据id查询blog
        String idStr = StrUtil.join(",", ids);
        List<Blog> blogs = query().in("id", ids).last("ORDER BY FIELD(id," + idStr + ")").list();

        for (Blog blog : blogs) {
            // 5.1.查询blog有关的用户
            queryBlogUser(blog);
            // 5.2.查询blog是否被点赞
            isBlogLiked(blog);
        }

        // 6.封装并返回
        ScrollResult r = new ScrollResult();
        r.setList(blogs);
        r.setOffset(os);
        r.setMinTime(minTime);

        return Result.ok(r);
    }

    /**
     * 查询blog有关的用户
     *
     * @param blog 笔记
     */
    private void queryBlogUser(Blog blog) {
        Long userId = blog.getUserId();
        User user = userService.getById(userId);
        blog.setName(user.getNickName());
        blog.setIcon(user.getIcon());
    }

    /**
     * 判断当前登录用户是否已经点赞该笔记
     *
     * @param blog 笔记
     */
    private void isBlogLiked(Blog blog) {
        // 1.获取登录用户
        UserDTO user = UserHolder.getUser();
        if (user == null) {
            // 未登录 无需点赞
            return;
        }
        Long userId = user.getId();
        // 2.判断当前登录用户是否已经点赞
        Double score = stringRedisTemplate.opsForZSet().score(BLOG_LIKED_KEY + blog.getId(), userId.toString());
        blog.setIsLike(BooleanUtil.isTrue(score != null));
    }

}

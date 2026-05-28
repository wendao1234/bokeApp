package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Follow;
import com.hmdp.mapper.FollowMapper;
import com.hmdp.service.IFollowService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.service.IUserService;
import com.hmdp.utils.UserHolder;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class FollowServiceImpl extends ServiceImpl<FollowMapper, Follow> implements IFollowService {
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private IUserService userService;

    /**
     * 关注或取关
     *
     * @param followUserId 关注的用户id
     * @param isFollow     是否关注
     * @return 成功或失败
     */
    @Override
    public Result follow(Long followUserId, Boolean isFollow) {
        // 1.获取当前用户
        UserDTO user = UserHolder.getUser();
        // 2.判断当前用户是否登录
        if (user == null) {
            return Result.fail("请先登录");
        }
        Long userId = user.getId();
        // 3.判断当前用户是否关注了该用户
        // 3.1.获取当前用户对关注用户的关注状态
        Integer count = lambdaQuery().eq(Follow::getUserId, userId).eq(Follow::getFollowUserId, followUserId).count();
        if (count > 0) {
            // 3.2.如果已经关注了，则取消关注
            boolean result = remove(new LambdaQueryWrapper<Follow>().eq(Follow::getUserId, userId).eq(Follow::getFollowUserId, followUserId));
            if (!result) {
                return Result.fail("取消关注失败");
            }
            String key = "follow:" + userId;
            stringRedisTemplate.opsForSet().remove(key, String.valueOf(followUserId));
            return Result.ok();
        } else {
            // 3.3.如果没有关注，则关注
            Follow follow = new Follow();
            follow.setUserId(userId);
            follow.setFollowUserId(followUserId);
            follow.setCreateTime(LocalDateTime.now());
            boolean result = save(follow);
            if (!result) {
                return Result.fail("关注失败");
            }
            String key = "follow:" + userId;
            stringRedisTemplate.opsForSet().add(key, String.valueOf(followUserId));
            return Result.ok();
        }
    }

    /**
     * 查询是否关注
     *
     * @param followUserId 关注的用户id
     * @return 成功或失败
     */
    @Override
    public Result isFollow(Long followUserId) {
        UserDTO user = UserHolder.getUser();
        if (user == null) {
            return Result.fail("请先登录");
        }
        Long userId = user.getId();
        Integer count = lambdaQuery().eq(Follow::getUserId, userId).eq(Follow::getFollowUserId, followUserId).count();
        return Result.ok(count > 0);
    }

    /**
     * 查询共同关注
     *
     * @param id 用户id
     * @return 共同关注列表
     */
    @Override
    public Result followCommons(Long id) {
        UserDTO user = UserHolder.getUser();
        if (user == null) {
            return Result.fail("请先登录");
        }
        Long userId = user.getId();
        String key1 = "follow:" + userId;
        String key2 = "follow:" + id;
        Set<String> intersect = stringRedisTemplate.opsForSet().intersect(key1, key2);
        if (intersect == null || intersect.isEmpty()) {
            return Result.ok(Collections.emptyList());
        }
        List<Long> ids = intersect.stream().map(Long::valueOf).collect(Collectors.toList());
        List<UserDTO> users = userService.listByIds(ids).stream().map(
                user1 -> BeanUtil.copyProperties(user1, UserDTO.class)).collect(Collectors.toList());
        return Result.ok(users);
    }
}

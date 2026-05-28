package com.hmdp.service.impl;

import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.BlogComments;
import com.hmdp.mapper.BlogCommentsMapper;
import com.hmdp.service.IAiContentModerationService;
import com.hmdp.service.IBlogCommentsService;
import com.hmdp.utils.UserHolder;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;

@Service
public class BlogCommentsServiceImpl extends ServiceImpl<BlogCommentsMapper, BlogComments> implements IBlogCommentsService {

    @Resource
    private IAiContentModerationService aiContentModerationService;

    @Override
    public Result saveComment(BlogComments comment) {
        UserDTO user = UserHolder.getUser();
        if (user == null) {
            return Result.fail("请先登录");
        }

        // AI内容审核
        IAiContentModerationService.ModerationResult moderationResult =
                aiContentModerationService.moderateTextWithDetail(comment.getContent());
        if (!moderationResult.isPass()) {
            return Result.fail(moderationResult.getReason());
        }

        comment.setUserId(user.getId());
        save(comment);
        return Result.ok(comment.getId());
    }
}
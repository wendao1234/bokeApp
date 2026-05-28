package com.hmdp.service;

import com.hmdp.dto.Result;
import com.hmdp.entity.BlogComments;
import com.baomidou.mybatisplus.extension.service.IService;

public interface IBlogCommentsService extends IService<BlogComments> {

    /**
     * 保存评论（含AI内容审核）
     */
    Result saveComment(BlogComments comment);
}
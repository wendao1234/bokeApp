package com.hmdp.controller;

import com.hmdp.dto.Result;
import com.hmdp.entity.BlogComments;
import com.hmdp.service.IBlogCommentsService;
import org.springframework.web.bind.annotation.*;

import javax.annotation.Resource;

@RestController
@RequestMapping("/blog-comments")
public class BlogCommentsController {

    @Resource
    private IBlogCommentsService blogCommentsService;

    @PostMapping
    public Result saveComment(@RequestBody BlogComments comment) {
        return blogCommentsService.saveComment(comment);
    }
}
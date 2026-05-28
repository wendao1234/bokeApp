package com.hmdp.controller;


import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Blog;
import com.hmdp.entity.User;
import com.hmdp.service.IBlogService;
import com.hmdp.service.IUserService;
import com.hmdp.service.IAiRecommendService;
import com.hmdp.utils.SystemConstants;
import com.hmdp.utils.UserHolder;
import org.springframework.web.bind.annotation.*;

import javax.annotation.Resource;
import java.util.List;

/**
 * <p>
 * 前端控制器
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@RestController
@RequestMapping("/blog")
public class BlogController {

    @Resource
    private IBlogService blogService;

    @Resource
    private IAiRecommendService aiRecommendService;

    @PostMapping
    public Result saveBlog(@RequestBody Blog blog) {
        return blogService.saveBlog(blog);
    }

    @GetMapping("{id}")
    public Result queryBlogById(@PathVariable("id") Long id) {
        // 记录用户浏览行为（用于推荐系统）
        UserDTO user = UserHolder.getUser();
        if (user != null) {
            aiRecommendService.recordBlogView(user.getId(), id);
        }
        return blogService.queryBlogById(id);
    }

    @GetMapping("likes/{id}")
    public Result queryBlogByLikes(@PathVariable("id") Long id) {
        return blogService.queryBlogByLikes(id);
    }

    @PutMapping("/like/{id}")
    public Result likeBlog(@PathVariable("id") Long id) {
        return blogService.likeBlog(id);
    }

    @GetMapping("/of/me")
    public Result queryMyBlog(@RequestParam(value = "current", defaultValue = "1") Integer current) {
        // 获取登录用户
        UserDTO user = UserHolder.getUser();
        // 根据用户查询
        Page<Blog> page = blogService.query()
                .eq("user_id", user.getId()).page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
        // 获取当前页数据
        List<Blog> records = page.getRecords();
        return Result.ok(records);
    }

    @GetMapping("/hot")
    public Result queryHotBlog(@RequestParam(value = "current", defaultValue = "1") Integer current) {
        return blogService.queryHotBlog(current);
    }

    /**
     * 查询指定用户发布的所有笔记
     * @param current 当前页
     * @param id 用户id
     * @return 笔记列表
     */
    @GetMapping("/of/user")
    public Result queryBlogByUserId(
            @RequestParam(value = "current", defaultValue = "1") Integer current,
            @RequestParam("id") Long id) {
        // 根据用户查询
        Page<Blog> page = blogService.query()
                .eq("user_id", id).page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
        // 获取当前页数据
        List<Blog> records = page.getRecords();
        return Result.ok(records);
    }
    /**
     * 查询指定用户发布笔记的分页数据
     * @param max 上一次查询的最大id
     * @param offset 偏移量
     * @return 笔记列表
     */
    @GetMapping("/of/follow")
    public Result queryBlogOfFollow(
            @RequestParam("lastId") Long max, @RequestParam(value = "offset", defaultValue = "0") Integer offset) {
        return blogService.queryBlogOfFollow(max, offset);
    }

    /**
     * AI推荐博客
     * @param limit 推荐数量
     * @return 推荐的博客列表
     */
    @GetMapping("/recommend")
    public Result recommendBlogs(@RequestParam(value = "limit", defaultValue = "10") Integer limit) {
        UserDTO user = UserHolder.getUser();
        if (user == null) {
            return Result.fail("请先登录");
        }
        List<Blog> blogs = aiRecommendService.recommendBlogs(user.getId(), limit);
        return Result.ok(blogs);
    }
}

package com.hmdp.controller;

import com.hmdp.dto.BlogCommentCreateDTO;
import com.hmdp.dto.Result;
import com.hmdp.service.IBlogCommentsService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.annotation.Resource;

/**
 * <p>
 *  前端控制器
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@RestController
@RequestMapping("/blog-comments")
public class BlogCommentsController {

    @Resource
    private IBlogCommentsService commentsService;

    /** 分页查询博客评论；下一步由前端渲染回复关系。 */
    @GetMapping("/blog/{blogId}")
    public Result queryByBlog(@PathVariable Long blogId,
                              @RequestParam(value = "current", defaultValue = "1") Integer current) {
        return commentsService.queryByBlog(blogId, current);
    }

    /** 创建评论或回复。 */
    @PostMapping
    public Result create(@RequestBody BlogCommentCreateDTO request) {
        return commentsService.createComment(request);
    }

    /** 删除当前用户自己的评论。 */
    @DeleteMapping("/{id}")
    public Result delete(@PathVariable("id") Long id) {
        return commentsService.deleteComment(id);
    }

    /** 点赞或取消点赞评论。 */
    @PutMapping("/like/{id}")
    public Result like(@PathVariable("id") Long id) {
        return commentsService.likeComment(id);
    }

}

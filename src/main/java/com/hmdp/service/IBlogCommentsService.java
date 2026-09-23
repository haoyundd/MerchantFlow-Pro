package com.hmdp.service;

import com.hmdp.entity.BlogComments;
import com.baomidou.mybatisplus.extension.service.IService;
import com.hmdp.dto.BlogCommentCreateDTO;
import com.hmdp.dto.Result;

/**
 * <p>
 *  服务类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
public interface IBlogCommentsService extends IService<BlogComments> {

    /** 查询博客评论，结果按时间倒序并包含回复关系。 */
    Result queryByBlog(Long blogId, Integer current);

    /** 创建一级评论或回复评论。 */
    Result createComment(BlogCommentCreateDTO request);

    /** 删除本人评论；管理员能力由控制层权限策略扩展。 */
    Result deleteComment(Long commentId);

    /** 点赞或取消点赞评论。 */
    Result likeComment(Long commentId);

}

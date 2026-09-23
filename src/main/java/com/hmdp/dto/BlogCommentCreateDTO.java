package com.hmdp.dto;

import lombok.Data;

/**
 * 评论创建请求。
 *
 * <p>parentId 为 0 表示一级评论，answerId 用于记录具体回复对象。</p>
 */
@Data
public class BlogCommentCreateDTO {

    /** 被评论的博客 ID。 */
    private Long blogId;

    /** 一级评论 ID；一级评论填写 0。 */
    private Long parentId;

    /** 被回复的评论 ID；一级评论填写 0。 */
    private Long answerId;

    /** 评论正文。 */
    private String content;
}

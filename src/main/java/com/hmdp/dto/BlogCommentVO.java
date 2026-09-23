package com.hmdp.dto;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 评论展示对象，隔离数据库实体，避免把内部字段直接暴露给前端。
 */
@Data
public class BlogCommentVO {

    private Long id;
    private Long userId;
    private String userName;
    private String userIcon;
    private Long blogId;
    private Long parentId;
    private Long answerId;
    private String content;
    private Integer liked;
    private Boolean isLike;
    private LocalDateTime createTime;
}

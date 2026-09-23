package com.hmdp.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.entity.BlogComments;
import com.hmdp.entity.Blog;
import com.hmdp.entity.User;
import com.hmdp.dto.BlogCommentCreateDTO;
import com.hmdp.dto.BlogCommentVO;
import com.hmdp.dto.Result;
import com.hmdp.mapper.BlogCommentsMapper;
import com.hmdp.service.IBlogService;
import com.hmdp.service.IBlogCommentsService;
import com.hmdp.service.IUserService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.UserHolder;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

import static com.hmdp.utils.RedisConstants.BLOG_COMMENT_LIKED_KEY;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class BlogCommentsServiceImpl extends ServiceImpl<BlogCommentsMapper, BlogComments> implements IBlogCommentsService {

    @Resource
    private IBlogService blogService;

    @Resource
    private IUserService userService;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    /** 查询正常评论；NULL 和 0 都代表旧数据中的正常状态。 */
    @Override
    public Result queryByBlog(Long blogId, Integer current) {
        if (blogService.getById(blogId) == null) {
            return Result.fail("博客不存在");
        }
        Page<BlogComments> page = page(new Page<>(current, 20), new QueryWrapper<BlogComments>()
                .eq("blog_id", blogId)
                .and(wrapper -> wrapper.isNull("status").or().eq("status", false))
                .orderByDesc("create_time"));
        List<BlogCommentVO> result = page.getRecords().stream()
                .map(this::toVO)
                .collect(Collectors.toList());
        return Result.ok(result, page.getTotal());
    }

    /** 创建评论并同步博客评论数；事务提交后才对外可见。 */
    @Override
    @Transactional
    public Result createComment(BlogCommentCreateDTO request) {
        if (request.getBlogId() == null || blogService.getById(request.getBlogId()) == null) {
            return Result.fail("博客不存在");
        }
        String content = request.getContent() == null ? "" : request.getContent().trim();
        if (content.isEmpty() || content.length() > 255) {
            return Result.fail("评论内容长度必须为 1 到 255 个字符");
        }
        long parentId = request.getParentId() == null ? 0L : request.getParentId();
        long answerId = request.getAnswerId() == null ? 0L : request.getAnswerId();
        if (parentId != 0L) {
            BlogComments parent = getById(parentId);
            if (parent == null || !Objects.equals(parent.getBlogId(), request.getBlogId())) {
                return Result.fail("回复的评论不存在或不属于当前博客");
            }
        }
        BlogComments comment = new BlogComments()
                .setUserId(UserHolder.getUser().getId())
                .setBlogId(request.getBlogId())
                .setParentId(parentId)
                .setAnswerId(answerId)
                .setContent(content)
                .setLiked(0)
                .setStatus(false);
        save(comment);
        blogService.update().eq("id", request.getBlogId()).setSql("comments = comments + 1").update();
        return Result.ok(toVO(comment));
    }

    /** 只允许作者删除评论，并同步博客评论数量。 */
    @Override
    @Transactional
    public Result deleteComment(Long commentId) {
        BlogComments comment = getById(commentId);
        if (comment == null) {
            return Result.fail("评论不存在");
        }
        if (!Objects.equals(comment.getUserId(), UserHolder.getUser().getId())) {
            return Result.fail("只能删除自己的评论");
        }
        removeById(commentId);
        blogService.update().eq("id", comment.getBlogId()).gt("comments", 0)
                .setSql("comments = comments - 1").update();
        stringRedisTemplate.delete(BLOG_COMMENT_LIKED_KEY + commentId);
        return Result.ok();
    }

    /** 使用 Redis Set 保证评论点赞幂等，再更新数据库计数。 */
    @Override
    public Result likeComment(Long commentId) {
        BlogComments comment = getById(commentId);
        if (comment == null) {
            return Result.fail("评论不存在");
        }
        String key = BLOG_COMMENT_LIKED_KEY + commentId;
        String userId = UserHolder.getUser().getId().toString();
        Boolean liked = stringRedisTemplate.opsForSet().isMember(key, userId);
        if (Boolean.TRUE.equals(liked)) {
            stringRedisTemplate.opsForSet().remove(key, userId);
            update().setSql("liked = greatest(coalesce(liked, 0) - 1, 0)").eq("id", commentId).update();
        } else {
            stringRedisTemplate.opsForSet().add(key, userId);
            update().setSql("liked = coalesce(liked, 0) + 1").eq("id", commentId).update();
        }
        return Result.ok();
    }

    /** 把评论实体和用户公开信息组装成前端对象。 */
    private BlogCommentVO toVO(BlogComments comment) {
        User user = userService.getById(comment.getUserId());
        BlogCommentVO vo = new BlogCommentVO();
        vo.setId(comment.getId());
        vo.setUserId(comment.getUserId());
        vo.setUserName(user == null ? "未知用户" : user.getNickName());
        vo.setUserIcon(user == null ? "" : user.getIcon());
        vo.setBlogId(comment.getBlogId());
        vo.setParentId(comment.getParentId());
        vo.setAnswerId(comment.getAnswerId());
        vo.setContent(comment.getContent());
        vo.setLiked(comment.getLiked() == null ? 0 : comment.getLiked());
        vo.setIsLike(Boolean.TRUE.equals(stringRedisTemplate.opsForSet()
                .isMember(BLOG_COMMENT_LIKED_KEY + comment.getId(), UserHolder.getUser().getId().toString())));
        vo.setCreateTime(comment.getCreateTime());
        return vo;
    }

}

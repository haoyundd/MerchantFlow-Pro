package com.hmdp.utils;

public class RedisConstants {
    public static final String LOGIN_CODE_KEY = "login:code:";
    public static final Long LOGIN_CODE_TTL = 2L;
    public static final String LOGIN_USER_KEY = "login:token:";
    public static final Long LOGIN_USER_TTL = 360L;

    public static final Long CACHE_NULL_TTL = 2L;

    public static final Long CACHE_SHOP_TTL = 30L;
    /** 店铺类型列表的 Redis TTL，避免后台调整类型后永久读取旧缓存。 */
    public static final Long CACHE_TYPE_LIST_TTL = 30L;
    public static final String CACHE_SHOP_KEY = "cache:shop:";
    public static final String CACHE_TYPE_LIST = "cache:shoplist:";

    public static final String LOCK_SHOP_KEY = "lock:shop:";
    public static final Long LOCK_SHOP_TTL = 10L;

    public static final String SECKILL_STOCK_KEY = "seckill:stock:";
    public static final String SECKILL_ORDER_KEY = "seckill:order:";
    public static final String BLOG_LIKED_KEY = "blog:liked:";
    /** 评论点赞集合，成员为用户 ID，用于实现评论点赞幂等。 */
    public static final String BLOG_COMMENT_LIKED_KEY = "blog:comment:liked:";
    public static final String FEED_KEY = "feed:";
    public static final String SHOP_GEO_KEY = "shop:geo:";
    public static final String USER_SIGN_KEY = "sign:";
    public static final String CHAT_SESSION_KEY = "chat:session:";
    public static final String CHAT_USER_SESSION_KEY = "chat:user_session:";
    public static final Long CHAT_SESSION_TTL = 30L;
}

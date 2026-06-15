package com.hmdp.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class CacheDeleteMessage implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 业务类型，例如 SHOP、VOUCHER，用于面试讲解和日志定位。
     */
    private String bizType;

    /**
     * 业务数据 id。
     */
    private String bizId;

    /**
     * 需要补偿删除的 Redis 缓存 key。
     */
    private String cacheKey;

    /**
     * 首次删除失败的原因，方便排查。
     */
    private String reason;
}

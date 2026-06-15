package com.hmdp.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.entity.ShopType;
import com.hmdp.mapper.ShopTypeMapper;
import com.hmdp.service.IShopTypeService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.List;

import static com.hmdp.utils.RedisConstants.CACHE_SHOP_KEY;
import static com.hmdp.utils.RedisConstants.CACHE_TYPE_LIST;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopTypeServiceImpl extends ServiceImpl<ShopTypeMapper, ShopType> implements IShopTypeService {
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    /**
     * 商铺类型业务添加缓存
     * @return
     */
    @Override
    public Result queryList() {
        String key = CACHE_TYPE_LIST;

        // 1. 从redis查询商铺缓存
        String typeJson = stringRedisTemplate.opsForValue().get(key);

        // 2. 判断是否存在
        if (StrUtil.isNotBlank(typeJson)) {
            // 3. 存在，直接返回
            List<ShopType>  shopTypeList= JSONUtil.toList(typeJson, ShopType.class);//转成实体类
            return Result.ok(shopTypeList);
        }
        // 4. 不存在，根据id查询数据库
        List<ShopType>  shopTypeList=query().orderByAsc("sort").list();

        // 5. 存在，写入redis
        stringRedisTemplate.opsForValue().set(key,JSONUtil.toJsonStr(shopTypeList));

        // 7. 返回
        return Result.ok(shopTypeList);

    }
}

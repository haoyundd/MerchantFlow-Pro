package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.lang.UUID;
import cn.hutool.core.util.RandomUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RegexUtils;
import com.hmdp.utils.UserHolder;
import io.netty.util.Timeout;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.connection.BitFieldSubCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.servlet.http.HttpSession;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;
import static com.hmdp.utils.SystemConstants.USER_NICK_NAME_PREFIX;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Slf4j
@Service
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {

    @Autowired
    private StringRedisTemplate stringRedisTemplate;
    @Override
    public Result sendCode(String phone, HttpSession session) {
        //1.校验手机号,是否符合规格，用正则表达式
        if(RegexUtils.isPhoneInvalid(phone)){
            //2.如果不符合，返回错误信息
            return  Result.fail("手机号格式错误");
        }

        //3.符合，生成验证码

        String code = RandomUtil.randomNumbers(6);

        //4.保存验证码到redis
         stringRedisTemplate.opsForValue().set(LOGIN_CODE_KEY+phone,code, LOGIN_CODE_TTL, TimeUnit.MINUTES);
        //5.发送验证码
        log.debug("发送短信验证码成功，验证码：{}",code);

        //返回ok
        return Result.ok();
    }

    /**
     * 登录功能
     * @param loginForm 登录参数，包含手机号、验证码；或者手机号、密码
     */
    @Override
    public Result login(LoginFormDTO loginForm, HttpSession session) {
        //1.校验手机号
        String phone = loginForm.getPhone();
        if(RegexUtils.isPhoneInvalid(phone)){
            //2.如果不符合，返回错误信息
            return  Result.fail("手机号格式错误");
        }
        //3.从redis获取验证码并校验验证码

        String cacheCode = stringRedisTemplate.opsForValue().get(LOGIN_CODE_KEY+phone);
        String code = loginForm.getCode();
        if(cacheCode==null||!cacheCode.equals(code)){
            //4.不一致报错
            return Result.fail("验证码错误");
        }

        //5.一致根据，手机号查询用户select * from tb_user where phone=?
        User user = query().eq("phone", phone).one();
        //6.判断用户是否存在
        if(user==null){
            //6.不存在，创建新用户并保存到session
         user= createUserWitjPhone(phone);
        }

        //7.存在，也保存用户信息到reids中
        //7.1生成一个token，作为登录令牌
        String token = UUID.randomUUID().toString(true);
        //7.2将User对象转换为hashmap存储
        UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);
        Map<String, Object> userMap = BeanUtil.beanToMap(
                userDTO,  // 源对象：要转换的 Bean（UserDTO）
                new HashMap<>(),  // 目标容器：转换后的 Map 存入这个新 HashMap
                CopyOptions.create()  // 转换配置项：定制转换规则
                        .setIgnoreNullValue(true)  // 规则1：忽略值为 null 的字段
                        .setFieldValueEditor((fieldName, fieldValue) -> fieldValue.toString())  // 规则2：所有值转成 String 类型
        );
        //7.3存储
        String tokenKey =LOGIN_USER_KEY+token;
        stringRedisTemplate.opsForHash().putAll(tokenKey, userMap);
     //7.4设置token的有效期
        stringRedisTemplate.expire(tokenKey,LOGIN_USER_TTL,TimeUnit.MINUTES);
        return Result.ok(token);
        //登录成功后,返回token
    }

    @Override
    public Result sign() {
// 1. 获取当前登录用户
        Long userId = UserHolder.getUser().getId();

// 2. 获取日期
        LocalDateTime now = LocalDateTime.now();

// 3. 拼接key
        String keySuffix = now.format(DateTimeFormatter.ofPattern(":yyyyMM"));
        String key = USER_SIGN_KEY + userId + keySuffix;

// 4. 获取今天是本月的第几天
        int dayOfMonth = now.getDayOfMonth();

// 5. 写入Redis SETBIT key offset 1
        stringRedisTemplate.opsForValue().setBit(key, dayOfMonth - 1, true);

        return Result.ok();

    }

    @Override
    public Result signCount() {
        // 1. 获取当前登录用户
        Long userId = UserHolder.getUser().getId();

// 2. 获取日期
        LocalDateTime now = LocalDateTime.now();

// 3. 拼接key
        String keySuffix = now.format(DateTimeFormatter.ofPattern(":yyyyMM"));
        String key = USER_SIGN_KEY + userId + keySuffix;

// 4. 获取今天是本月的第几天
        int dayOfMonth = now.getDayOfMonth();
// 5. 获取本月截止今天为止的所有的签到记录，返回的是一个十进制的数字 BITFIELD sign:5:202203 GET u14 0
        List<Long> result = stringRedisTemplate.opsForValue().bitField(
                key,
                BitFieldSubCommands.create()
                        .get(BitFieldSubCommands.BitFieldType.unsigned(dayOfMonth)).valueAt(0)
        );//是因为 Redis 的 BITFIELD 命令支持一次执行多个子命令，每个子命令都会返回一个结果，
        // 所以 Spring Data Redis 用集合来承载多个返回值。

        if (result == null || result.isEmpty()) {
            // 没有任何签到结果
            return Result.ok(0);
        }
        Long num = result.get(0);
        if (num == null || num == 0) {
            return Result.ok(0);
        }
// 6. 循环遍历
        int count = 0;
        while (true) {
            // 6.1. 让这个数字与1做与运算，得到数字的最后一个bit位 // 判断这个bit位是否为0
            if ((num & 1) == 0) {
                // 如果为0，说明未签到，结束
                break;
            } else {
                // 如果不为0，说明已签到，计数器+1
                count++;
            }
            // 把数字右移一位，抛弃最后一个bit位，继续下一个bit位
            num >>>= 1;
        }
        return Result.ok(count);

    }

    private User createUserWitjPhone(String phone) {
        //创建用户
        User user = new User();
        user.setPhone(phone);
        user.setNickName(USER_NICK_NAME_PREFIX+RandomUtil.randomString(10));
         //保存用户
        save(user);
        return user;
    }
}

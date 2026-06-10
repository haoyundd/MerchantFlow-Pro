package com.hmdp.utils;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.Month;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

@Component
public class RedisIdWorker {
    /**
     * 开始的时间戳
     */
    private static final long BEGIN_TIMESTAMP=1767225600L;
    /**
     * 序列号位数
     */
    private static final int COUNT_BITS=32;
    @Autowired
    private StringRedisTemplate stringRedisTemplate;
    public long nextId(String keyPrefix) {//区分不同的业务，业务的前缀

        //1.生成时间戳
        LocalDateTime now=LocalDateTime.now();
        long nowSecond = now.toEpochSecond(ZoneOffset.UTC);
        long timetamp=nowSecond-BEGIN_TIMESTAMP;

        //2.生成序列号
        //2.1获取当前日期，精确到天
        String date = now.format(DateTimeFormatter.ofPattern("yyyy:MM:dd"));

        //2.2自增长、、该 Key 对应的「Value」是数字格式的字符串，increment() 对其进行自增，最终转换为 Java 的 Long 类型返回；
        long count = stringRedisTemplate.opsForValue().increment("icr:" + keyPrefix + ":" + date);


        //3.拼接并返回
        return timetamp<<COUNT_BITS|count;//先把时间戳网左移动序列号的位数，然后用或运算进行拼接


    }


}

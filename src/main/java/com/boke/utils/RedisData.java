package com.hmdp.utils;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class RedisData {
    /**
     * 缓存过期时间
     */
    private LocalDateTime expireTime;
    /**
     * 缓存数据
     */
    private Object data;
}

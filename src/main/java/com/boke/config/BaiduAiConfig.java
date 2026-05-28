package com.hmdp.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "baidu.ai")
public class BaiduAiConfig {
    private String apiKey;
    private String secretKey;
}

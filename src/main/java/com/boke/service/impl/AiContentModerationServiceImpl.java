package com.hmdp.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.config.BaiduAiConfig;
import com.hmdp.service.IAiContentModerationService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.*;
import java.util.regex.Pattern;

/**
 * AI内容审核服务实现类
 * 双层审核策略：
 *   第一层：本地DFA敏感词预过滤（快速拦截明显违规）
 *   第二层：百度AI内容安全API（ML模型精准识别）
 *   兜底：API不可用时降级为纯本地DFA审核
 */
@Slf4j
@Service
public class AiContentModerationServiceImpl implements IAiContentModerationService {

    @Resource
    private BaiduAiConfig baiduAiConfig;

    private final ObjectMapper objectMapper = new ObjectMapper();

    // access_token缓存
    private volatile String accessToken;
    private volatile long tokenExpireTime = 0;

    // 敏感词库（本地DFA预过滤用）
    private static final Set<String> SENSITIVE_WORDS = new LinkedHashSet<>(Arrays.asList(
            "赌博", "色情", "暴力", "反动", "诈骗", "毒品",
            "枪支", "爆炸", "恐怖", "政治敏感", "违法", "欺诈"
    ));

    // 广告关键词
    private static final Set<String> AD_KEYWORDS = new LinkedHashSet<>(Arrays.asList(
            "加微信", "扫码", "代购", "刷单", "兼职", "贷款",
            "办证", "发票", "私聊", "联系方式"
    ));

    private final DfaWordFilter sensitiveWordFilter = new DfaWordFilter(SENSITIVE_WORDS);

    private static final Pattern PHONE_PATTERN = Pattern.compile("(?<![\\d])1[3-9]\\d{9}(?![\\d])");
    private static final Pattern WECHAT_PATTERN = Pattern.compile("(?i)(微信|vx|wx)[：:\\s]*[a-zA-Z0-9_-]{5,20}");
    private static final Pattern URL_PATTERN = Pattern.compile("(?:https?://|www\\.)\\S+");

    @Override
    public boolean moderateText(String text) {
        return moderateTextWithDetail(text).isPass();
    }

    @Override
    public ModerationResult moderateTextWithDetail(String text) {
        if (StrUtil.isBlank(text)) {
            return new ModerationResult(true, null);
        }

        // ========== 第一层：本地DFA预过滤（快速拦截明显违规）==========
        ModerationResult localResult = localPreCheck(text);
        if (!localResult.isPass()) {
            log.info("本地DFA预过滤拦截: {}", localResult.getReason());
            return localResult;
        }

        // ========== 第二层：百度AI内容安全API（ML模型精准识别）==========
        try {
            ModerationResult apiResult = callBaiduModeration(text);
            if (apiResult != null) {
                return apiResult;
            }
        } catch (Exception e) {
            log.warn("百度AI审核API调用失败，降级为本地DFA审核: {}", e.getMessage());
        }

        // API不可用时降级：本地DFA审核已通过，放行
        log.info("本地DFA审核通过（API降级）");
        return new ModerationResult(true, null);
    }

    /**
     * 本地DFA预过滤
     */
    private ModerationResult localPreCheck(String text) {
        // 1. DFA敏感词检测
        List<int[]> matches = sensitiveWordFilter.findAll(text);
        if (!matches.isEmpty()) {
            String word = text.substring(matches.get(0)[0], matches.get(0)[1]);
            return new ModerationResult(false, "内容包含敏感词，请修改后重试");
        }
        // 2. 广告检测
        int adCount = 0;
        for (String keyword : AD_KEYWORDS) {
            if (text.contains(keyword)) adCount++;
        }
        if (adCount >= 2) {
            return new ModerationResult(false, "内容疑似广告，请勿发布营销信息");
        }
        // 3. 联系方式检测
        if (PHONE_PATTERN.matcher(text).find()) {
            return new ModerationResult(false, "请勿在内容中留下联系方式");
        }
        if (WECHAT_PATTERN.matcher(text).find()) {
            return new ModerationResult(false, "请勿在内容中留下联系方式");
        }
        // 4. 外链检测
        if (URL_PATTERN.matcher(text).find()) {
            return new ModerationResult(false, "请勿在内容中添加外部链接");
        }
        // 5. 内容质量
        if (text.length() < 10) {
            return new ModerationResult(false, "内容过短，请至少输入10个字符");
        }
        if (hasExcessiveRepetition(text)) {
            return new ModerationResult(false, "内容包含大量重复字符，请认真填写");
        }
        return new ModerationResult(true, null);
    }

    /**
     * 调用百度AI内容安全API
     */
    private ModerationResult callBaiduModeration(String text) throws Exception {
        String token = getAccessToken();
        if (token == null) {
            return null; // token获取失败，返回null触发降级
        }

        // 调用百度文本审核API
        String url = "https://aip.baidubce.com/rest/2.0/solution/v1/text_censor/v2/user_defined" + "?access_token=" + token;
        String body = "text=" + cn.hutool.core.util.URLUtil.encodeAll(text);

        HttpResponse response = HttpRequest.post(url)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .body(body)
                .timeout(5000)
                .execute();

        if (response.getStatus() != 200) {
            log.warn("百度AI API返回异常状态码: {}", response.getStatus());
            return null;
        }

        return parseBaiduResponse(response.body());
    }

    /**
     * 获取百度AI access_token（带本地缓存）
     */
    private String getAccessToken() {
        // 缓存未过期，直接返回
        if (accessToken != null && System.currentTimeMillis() < tokenExpireTime) {
            return accessToken;
        }
        synchronized (this) {
            if (accessToken != null && System.currentTimeMillis() < tokenExpireTime) {
                return accessToken;
            }
            try {
                String url = "https://aip.baidubce.com/oauth/2.0/token"
                        + "?grant_type=client_credentials"
                        + "&client_id=" + baiduAiConfig.getApiKey()
                        + "&client_secret=" + baiduAiConfig.getSecretKey();
                HttpResponse response = HttpRequest.post(url).timeout(5000).execute();
                if (response.getStatus() == 200) {
                    JsonNode json = objectMapper.readTree(response.body());
                    accessToken = json.get("access_token").asText();
                    long expiresIn = json.get("expires_in").asLong();
                    // 提前5分钟刷新
                    tokenExpireTime = System.currentTimeMillis() + (expiresIn - 300) * 1000;
                    log.info("百度AI access_token获取成功，有效期{}秒", expiresIn);
                    return accessToken;
                }
                log.warn("获取百度AI access_token失败，状态码: {}", response.getStatus());
            } catch (Exception e) {
                log.error("获取百度AI access_token异常", e);
            }
        }
        return null;
    }

    /**
     * 解析百度AI审核响应
     * conclusionType: 1=合规, 2=疑似, 3=不合规
     */
    private ModerationResult parseBaiduResponse(String responseBody) {
        try {
            JsonNode json = objectMapper.readTree(responseBody);
            int conclusionType = json.has("conclusionType") ? json.get("conclusionType").asInt() : 1;

            if (conclusionType == 1) {
                // 审核通过
                return new ModerationResult(true, null);
            }

            // 审核不通过或疑似，提取具体原因
            String reason = "内容审核未通过，请修改后重试";
            if (json.has("data")) {
                JsonNode data = json.get("data");
                StringBuilder sb = new StringBuilder();
                for (JsonNode item : data) {
                    if (item.has("msg")) {
                        if (sb.length() > 0) sb.append("；");
                        sb.append(item.get("msg").asText());
                    }
                }
                if (sb.length() > 0) {
                    reason = "内容审核未通过：" + sb.toString();
                }
            }

            log.warn("百度AI审核不通过: conclusionType={}, reason={}", conclusionType, reason);
            return new ModerationResult(false, reason);

        } catch (Exception e) {
            log.warn("解析百度AI响应异常", e);
            return null; // 解析失败，触发降级
        }
    }

    private boolean hasExcessiveRepetition(String text) {
        if (text.length() < 10) return false;
        int maxRepeat = 0, currentRepeat = 1;
        char lastChar = text.charAt(0);
        for (int i = 1; i < text.length(); i++) {
            if (text.charAt(i) == lastChar) {
                currentRepeat++;
                maxRepeat = Math.max(maxRepeat, currentRepeat);
            } else {
                currentRepeat = 1;
                lastChar = text.charAt(i);
            }
        }
        return maxRepeat > 8;
    }

    /**
     * DFA（确定有限状态自动机）多模式匹配器
     * 一次遍历文本即可匹配所有模式串，时间复杂度O(n+m)
     */
    private static class DfaWordFilter {
        private static class State {
            Map<Character, State> transitions = new HashMap<>();
            boolean accept;
        }
        private final State root = new State();

        DfaWordFilter(Set<String> words) {
            for (String word : words) {
                State cur = root;
                for (int i = 0; i < word.length(); i++) {
                    char ch = word.charAt(i);
                    cur = cur.transitions.computeIfAbsent(ch, c -> new State());
                }
                cur.accept = true;
            }
        }

        List<int[]> findAll(String text) {
            List<int[]> result = new ArrayList<>();
            for (int i = 0; i < text.length(); i++) {
                State cur = root;
                int matchEnd = -1;
                for (int j = i; j < text.length(); j++) {
                    cur = cur.transitions.get(text.charAt(j));
                    if (cur == null) break;
                    if (cur.accept) matchEnd = j + 1;
                }
                if (matchEnd != -1) result.add(new int[]{i, matchEnd});
            }
            return result;
        }
    }
}

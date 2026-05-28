package com.hmdp.service;

/**
 * AI内容审核服务接口
 */
public interface IAiContentModerationService {

    /**
     * 审核文本内容
     * @param text 待审核的文本
     * @return true-审核通过, false-审核不通过
     */
    boolean moderateText(String text);

    /**
     * 审核文本内容并返回详细信息
     * @param text 待审核的文本
     * @return 审核结果详情
     */
    ModerationResult moderateTextWithDetail(String text);

    /**
     * 审核结果
     */
    class ModerationResult {
        private boolean pass;
        private String reason;

        public ModerationResult(boolean pass, String reason) {
            this.pass = pass;
            this.reason = reason;
        }

        public boolean isPass() {
            return pass;
        }

        public String getReason() {
            return reason;
        }
    }
}

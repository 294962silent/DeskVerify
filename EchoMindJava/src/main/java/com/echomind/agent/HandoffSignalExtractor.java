package com.echomind.agent;

import com.echomind.intent.IntentCategory;
import com.echomind.intent.UrgencyLevel;
import java.util.Locale;
import org.springframework.stereotype.Component;

@Component
public final class HandoffSignalExtractor {

    public HandoffSignals from(AgentRequest request, AgentResponse response) {
        String message = request.message() == null
                ? ""
                : request.message().toLowerCase(Locale.ROOT);
        IntentCategory intent = request.intent();
        return new HandoffSignals(
                response.escalate()
                        || intent == IntentCategory.ESCALATION
                        || intent == IntentCategory.HUMAN_HANDOFF
                        || containsAny(message,
                        "转人工", "人工客服", "找人工", "真人", "找你们经理", "投诉"),
                request.urgency() == UrgencyLevel.CRITICAL
                        || containsAny(message,
                        "完全不可用", "全部无法登录", "生产宕机", "全站故障"),
                intent == IntentCategory.ACCOUNT_SECURITY
                        || containsAny(message,
                        "账号被盗", "异地登录", "数据泄露", "隐私泄露", "密码泄露"),
                containsAny(message,
                        "重复扣款", "扣了两次", "多扣", "退款争议", "拒付", "chargeback"),
                containsAny(message,
                        "永久删除", "删除全部", "注销租户", "注销账户", "关闭账户"),
                response.success()
        );
    }

    private static boolean containsAny(String text, String... terms) {
        for (String term : terms) {
            if (text.contains(term)) {
                return true;
            }
        }
        return false;
    }
}

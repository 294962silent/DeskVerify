package com.echomind.api.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@Schema(description = "智能客服对话请求")
public record ChatRequest(
        @Schema(description = "用户消息", example = "我想申请退款，订单号是 #12345")
        @NotBlank String message,
        @Schema(description = "会话 ID，未传时服务端自动生成", example = "conv-001")
        @JsonProperty("conv_id")
        @JsonAlias({"conv_id", "conversation_id"})
        String conversationId,
        @Schema(description = "订单查询或人工工单使用的已授权客户 ID", example = "customer-001")
        @JsonProperty("customer_id")
        @Size(max = 128) String customerId
) {
    public ChatRequest(String message, String conversationId) {
        this(message, conversationId, null);
    }
}

package io.github.chocoemmm.danmakulab.config;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * 输出侧实验参数，独立于输入限流：输入每秒几条与为观众缓存几字节是两个问题。
 * mode=direct 保留同步基线，buffered 启用每连接邮箱；默认配置不声称已经通过容量验收。
 * bytes 包含完整 UTF-8 JSON；messages/bytes 同时包含正在发送但尚未返回的那一条。
 * workers 是所有连接共享的发送线程数，绝不按连接数创建线程。
 */
@Validated
@ConfigurationProperties("lab.outbound")
public record OutboundProperties(
        @Pattern(regexp = "direct|buffered") String mode,
        @Min(1) int maxMessages,
        @Min(1) long maxBytes,
        @Min(1) long globalBytes,
        @Min(1) int workers,
        @Min(1) int taskCapacity,
        @Min(1) int maxAgeMillis,
        @Min(1) int batchSize) {
    public boolean buffered() {
        return "buffered".equals(mode);
    }
}

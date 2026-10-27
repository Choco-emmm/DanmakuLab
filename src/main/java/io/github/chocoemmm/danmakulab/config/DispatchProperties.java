package io.github.chocoemmm.danmakulab.config;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * 房间输入调度参数，与 lab.outbound 的观众输出缓冲是两层不同预算。
 * direct 保留原广播路径；fair 将弹幕交给房间 FIFO（先入队的先处理），轮流分批分发。
 * 消息数/字节包括正在分发的一条；字节按完整广播 UTF-8 JSON 计算，不代表 JVM 总内存。
 */
@Validated
@ConfigurationProperties("lab.dispatch")
public record DispatchProperties(
        @Pattern(regexp = "direct|fair") String mode,
        @Min(1) int maxMessages,
        @Min(1) long maxBytes,
        @Min(1) int globalMessages,
        @Min(1) long globalBytes,
        @Min(1) int batchMessages,
        @Min(1) int batchRecipients,
        @Min(1) int maxAgeMillis) {
    public boolean fair() { return "fair".equals(mode); }
}

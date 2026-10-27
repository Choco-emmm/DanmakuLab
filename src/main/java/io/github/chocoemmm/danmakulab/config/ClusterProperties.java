package io.github.chocoemmm.danmakulab.config;

import jakarta.validation.constraints.*;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** 节点间消息总线配置；所有容量包含正在处理的一条，字节均为完整信封UTF-8载荷。 */
@Validated
@ConfigurationProperties("lab.cluster")
public record ClusterProperties(
        @Pattern(regexp="local|redis") String mode,
        @Pattern(regexp="[A-Za-z0-9_-]{1,32}") String nodeId,
        @Pattern(regexp="[A-Za-z0-9_-]{1,64}") String namespace,
        @NotBlank String redisUri,
        @Min(1) @Max(4096) int maxEventBytes,
        @Min(1) int queueMessages,
        @Min(1) long queueBytes,
        @Min(1) int maxAgeMillis,
        @Min(1) int timeoutMillis) {
    public String channel() { return namespace + ":broadcast:v1"; }
}

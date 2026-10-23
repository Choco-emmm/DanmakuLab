package io.github.chocoemmm.danmakulab.config;

import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * 把 application.yml 中 lab.* 配置映射为一个只读对象，由 Spring 注入到需要它的组件。
 * record 是 Java 的简洁数据载体：自动生成构造器和 maxConnections() 等访问方法。
 * Validated 配合 Min 在启动时校验参数，避免服务带着零容量、负速率等错误配置运行。
 *
 * @param maxConnections 当前服务允许登记的 WebSocket 连接总数，不等同于已验证的容量成绩
 * @param maxRooms 同时存在的非空房间上限
 * @param maxFrameBytes 完整输入 JSON 的 UTF-8 字节上限，包含字段名、正文和时间戳
 * @param maxTextLength 正文 String.length() 的上限，单位为 UTF-16 代码单元，部分表情占两个单位
 * @param connectionMessagesPerSecond 单连接令牌桶的每秒补充量，同时作为该桶容量
 * @param roomMessagesPerSecond 单房间令牌桶的每秒补充量，同时作为该桶容量
 */
@Validated
@ConfigurationProperties("lab")
public record LabProperties(
        @Min(1) int maxConnections,
        @Min(1) int maxRooms,
        @Min(128) int maxFrameBytes,
        @Min(1) int maxTextLength,
        @Min(1) int connectionMessagesPerSecond,
        @Min(1) int roomMessagesPerSecond) {
}

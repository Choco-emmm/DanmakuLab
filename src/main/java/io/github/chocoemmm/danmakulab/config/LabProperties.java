package io.github.chocoemmm.danmakulab.config;

import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

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

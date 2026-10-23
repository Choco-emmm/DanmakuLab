package io.github.chocoemmm.danmakulab.protocol;

/**
 * 可以向客户端解释的业务拒绝，例如未加入房间、字段非法或限流。
 * message 保存稳定原因码，由 DanmakuHandler 统一转换为协议响应和分类指标。
 */
public class ProtocolException extends RuntimeException {
    public ProtocolException(String reason) {
        super(reason);
    }
}

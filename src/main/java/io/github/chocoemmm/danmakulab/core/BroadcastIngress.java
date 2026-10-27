package io.github.chocoemmm.danmakulab.core;

import java.io.IOException;

/** 通过协议校验与节点内限流后的广播入口；实现负责定义accepted，而不是协议处理器猜测投递结果。 */
public interface BroadcastIngress {
    void submit(ClientConnection sender, ConnectionRegistry.Membership membership,
                String id, String text, long sentAt) throws IOException;
}

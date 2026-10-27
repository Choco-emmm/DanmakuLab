package io.github.chocoemmm.danmakulab.core;

import java.io.IOException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** 默认本地适配器：只搬移路由判断，不改变direct/fair的序号、计数或确认语义。 */
@Component
@ConditionalOnProperty(name="lab.cluster.mode", havingValue="local", matchIfMissing=true)
public class LocalBroadcastIngress implements BroadcastIngress {
    private final DirectBroadcaster broadcaster;
    private final FairRoomDispatcher dispatcher;
    public LocalBroadcastIngress(DirectBroadcaster broadcaster, FairRoomDispatcher dispatcher) {
        this.broadcaster = broadcaster;
        this.dispatcher = dispatcher;
    }
    @Override
    public void submit(ClientConnection sender, ConnectionRegistry.Membership membership,
                       String id, String text, long sentAt) throws IOException {
        if (dispatcher.enabled()) dispatcher.submit(sender, membership, id, text, sentAt);
        else broadcaster.broadcast(sender, membership, id, text, sentAt);
    }
}

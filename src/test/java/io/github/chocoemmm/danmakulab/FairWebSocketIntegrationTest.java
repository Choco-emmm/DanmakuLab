package io.github.chocoemmm.danmakulab;

import io.github.chocoemmm.danmakulab.core.FairRoomDispatcher;
import io.github.chocoemmm.danmakulab.core.OutboundService;
import org.junit.jupiter.api.AfterEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/** 用真实 Tomcat 再验证一遍 fair+buffered；故意将观众批次设为 2，让十人广播跨多个轮次。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "lab.outbound.mode=buffered", "lab.dispatch.mode=fair", "lab.dispatch.batch-recipients=2" })
class FairWebSocketIntegrationTest extends WebSocketIntegrationTest {
    @Autowired FairRoomDispatcher dispatcher;
    @Autowired OutboundService outbound;

    @AfterEach
    void bothLayersReleaseTheirBudgets() {
        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> {
            assertThat(dispatcher.bufferedBytes()).isZero();
            assertThat(dispatcher.bufferedMessages()).isZero();
            assertThat(outbound.bufferedBytes()).isZero();
            assertThat(outbound.bufferedMessages()).isZero();
        });
    }
}

package io.github.chocoemmm.danmakulab;

import io.github.chocoemmm.danmakulab.core.OutboundService;
import org.junit.jupiter.api.AfterEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * 将同一套真实网络协议测试在 buffered 模式再运行一遍，防止优化破坏加入、隔离、顺序与清理。
 * 继承仅复用场景，不复制断言；Spring 用不同配置启动另一个随机端口应用上下文。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "lab.outbound.mode=buffered")
class BufferedWebSocketIntegrationTest extends WebSocketIntegrationTest {
    @Autowired OutboundService outbound;

    @AfterEach
    void budgetEventuallyReturnsToZero() {
        // 不主动关闭客户端的场景也可能仍在收尾，给在途写入返回和邮箱释放有限时间。
        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> {
            assertThat(outbound.bufferedBytes()).isZero();
            assertThat(outbound.bufferedMessages()).isZero();
        });
    }
}

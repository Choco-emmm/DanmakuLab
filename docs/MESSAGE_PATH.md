# 从一条弹幕看懂两层队列与异步发送

本文讲当前`lab.cluster.mode=local`、`lab.dispatch.mode=fair`、`lab.outbound.mode=buffered`的路径。后两个开关默认都是direct；只启动默认服务不会经过本文全部步骤。Java源码保持不变，本文是阅读导航；跨节点路径见 [Redis双节点](CLUSTER.md)。

假设房间里有A、B、C三位观众，A发出“你好”。广播也发给A自己，所以会产生三份观众投递。

```text
A的WebSocket发来send
  → DanmakuHandler.send：校验、连接限流、房间限流
  → BroadcastIngress.submit：local模式由LocalBroadcastIngress选择本地调度
  → FairRoomDispatcher.submit：给房间队列追加一次“你好”
  → lab-room-dispatch线程：轮到该房间，取得成员快照[A,B,C]
  → DirectBroadcaster.deliver：分别投递到A、B、C的输出邮箱
  → OutboundMailbox.drain：共享提交线程取出某连接的一条消息
  → ClientConnection.writeAsync：调用容器Async.sendText
  → 容器完成/失败回调
  → OutboundMailbox.finish：结算这次写，必要时激活后续消息
```

## 1. 房间队列：先收下一条待广播输入

打开[DanmakuHandler](../src/main/java/io/github/chocoemmm/danmakulab/protocol/DanmakuHandler.java)，找到`send`最后的`ingress.submit(...)`。在local模式下，沿[LocalBroadcastIngress.submit](../src/main/java/io/github/chocoemmm/danmakulab/core/LocalBroadcastIngress.java)找到`dispatcher.submit(...)`；fair开启时才进入房间调度器，原房间调度会走另一条路径。

再看[FairRoomDispatcher](../src/main/java/io/github/chocoemmm/danmakulab/core/FairRoomDispatcher.java)的`submit`：

```java
queue.entries.addLast(new Pending(message, clock.getAsLong()));
```

`addLast`是追加到队尾。这时保存的是一次待广播的弹幕，不是把500个观众各创建一条房间输入。房间/全局的条数和字节预算检查通过后，才分配序号、计accepted、加入就绪队列。锁保护这些共享状态；放锁后才给发送者投递accepted控制回复。

**accepted说明系统接受过这条输入，仍可能因后续过期或输出失败而不能交付。** 调度线程可能先于确认分发广播，客户端不能假设accepted一定先到。

## 2. 房间调度线程：分批把任务交给观众邮箱

在同一文件找到`dispatchOneTurn`。它从`ready`就绪队列头取出一个房间，首次实际分发时复制成员快照，然后依次调用：

```java
broadcaster.deliver(pending.recipients.get(pending.cursor), pending.message);
```

快照是当次目标名单的副本，后续批次复用，避免房间成员变化时重复遍历。同房间第一条没有遍历完，不开始第二条。

默认每轮最多尝试64份观众投递，或完成4条输入，先达到哪一个就让出。500人房间投递前64人后，保留游标，把房间放到就绪队尾，下次从第65人继续。它是一种限制单轮工作量的轮转规则，不承诺任何负载下都更快。

## 3. 连接邮箱：每个观众各自排队

沿[DirectBroadcaster.deliver](../src/main/java/io/github/chocoemmm/danmakulab/core/DirectBroadcaster.java)进入`reply → connection.send`，再看[ClientConnection.send](../src/main/java/io/github/chocoemmm/danmakulab/core/ClientConnection.java)：

```java
if (mailbox == null) writeNow(message);
else mailbox.offer(message, danmaku);
```

buffered模式执行`mailbox.offer`。在[OutboundMailbox](../src/main/java/io/github/chocoemmm/danmakulab/core/OutboundMailbox.java)中，邮箱使用`ArrayDeque<Entry>`保存这个连接等待写出的消息；入队检查本连接与全局预算。弹幕、pong、joined和发送结果共用这一个出口，控制消息不会被悄悄当弹幕淘汰。

邮箱不是某个线程专属的任务队列。A、B、C各有自己的邮箱，共享8个提交线程；线程池的任务队列里放“处理这个邮箱”的任务。

## 4. 提交异步写：把网络等待交给容器

`offer`在邮箱从空闲转为活跃时安排`drain`，不为每次入队重复安排一个发送者。`drain`从队首取一条，放到`inFlight`，然后调用：

```java
connection.writeAsync(pending.entry.message(), failure ->
        finish(pending, failure, failure == null ? null : "write_failure"));
```

`writeAsync`内部使用：

```java
asyncRemote.sendText(message.getPayload(), result ->
        completion.accept(result.getException()));
```

右侧的lambda是回调：容器完成或失败时执行它。提交线程不调用`Future.get()`等待。如果本次尚未完成，`drain`返回，共享线程可以处理B、C等其他邮箱；A仍保留`active=true`和一条`inFlight`，不允许给A再发第二条。

回调也可能在`sendText`返回前就同步发生。`invoking`用于区分这个情况，避免回调递归调用下一次发送；当前提交者会在有限批次内继续推进。重复通知由`completed`保护，只结算一次。

## 5. 完成回调：归还预算，再考虑下一条

找到`finish`。它标记本次完成，清除`inFlight`，归还条数/字节预算，成功时计submitted；有后续消息则重新安排该邮箱。失败时先禁止继续发送，再注销、清理并关闭连接。

消息从队列取出时仍保留预算，因为它还在等待网络写完成。关闭连接和写完成是两个独立事件，名额要等两者都收尾才能复用，避免旧写尚在途又不断准入新连接。

假设A很慢，B、C正常：

```text
A邮箱：当前一条写等待完成；后续消息在有界邮箱中排队
B邮箱：提交 → 回调完成 → 下一条
C邮箱：提交 → 回调完成 → 下一条
共享提交线程：不因等待A的写完成而一直占用
```

慢A仍可能积压、丢弃旧弹幕或被断开。共享CPU、容器缓冲和网络也仍有上限，不能据此声称任意慢连接比例都完全不影响其他人。

| 看到的事件 | 能说明什么 |
| --- | --- |
| accepted | 该输入通过准入并进入过房间队列 |
| 邮箱入队成功 | 该观众的一份输出被有界缓冲接纳 |
| 异步完成成功、submitted增加 | 容器报告这次写成功完成 |
| 压测观众解析并去重计数 | 这个观众实际收到并识别了该弹幕 |

阅读顺序：先找到上面的六个方法，不必一开始读懂所有构造器。能解释“在哪排队、谁来取、什么时候释放预算、成功是哪一种成功”，就已经抓住消息路径。

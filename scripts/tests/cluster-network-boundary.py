"""显式双节点边界回归：A的正文上限300、B保持200，两者使用隔离的danmakulab-prepublish频道。

要求先按本轮审查记录启动8087/8088和专用Redis。复用真实TCP/WebSocket客户端，
不以服务器send调用代替实际接收；只向专用频道注入一条非法版本，不停止任何服务。
用法：python -X utf8 scripts/tests/cluster-network-boundary.py 结果.json
"""
import asyncio
import json
from pathlib import Path
import subprocess
import sys
import time
from types import SimpleNamespace
from urllib.error import HTTPError
from urllib.request import urlopen

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'load'))
from longrun import Peer


class ProbeState:
    """每节点各一份本地房间实例；事件列表仅容纳本测试的两条输入，读失败明确计入结果。"""
    def __init__(self, port, room):
        self.args = SimpleNamespace(url=f'ws://127.0.0.1:{port}/ws', slow_connections=0)
        self.room, self.instance, self.joined = room, None, 0
        self.measuring = self.closing = False
        self.protocol_errors = 0
        self.events, self.failures = [], []

    def event(self, peer, event):
        self.events.append(event)

    def failure(self, peer, operation, error):
        self.failures.append(f'{operation}: {error}')


async def until(predicate):
    """总等待预算3秒，不在每次轮询后重置超时；失败保留断言而不把缺收当成功。"""
    deadline = time.monotonic() + 3
    while not predicate():
        assert time.monotonic() < deadline, '未在3秒内收到预期事件'
        await asyncio.sleep(.01)


def invalid_version_count(port):
    """首次丢弃之前计数器尚不存在，404按零读；比较前后增量，允许同一节点重复运行本测试。"""
    try:
        with urlopen(f'http://127.0.0.1:{port}/actuator/metrics/lab.cluster.dropped?tag=reason:invalid_event', timeout=3) as response:
            return json.load(response)['measurements'][0]['value']
    except HTTPError as error:
        if error.code == 404:
            return 0
        raise


async def verify():
    room = 'boundary-' + str(time.time_ns())
    states = [ProbeState(port, room) for port in (8087, 8088)]
    peers = [Peer(state, index) for index, state in enumerate(states)]
    try:
        before = {port: invalid_version_count(port) for port in (8087, 8088)}
        await asyncio.gather(*(peer.open() for peer in peers))
        for index, (content, sender) in enumerate((('a' * 201, 0), ('reply', 1))):
            message_id = f'boundary-{index}'
            await peers[sender].send(dict(type='send', clientMessageId=message_id,
                                          text=content, clientSentAt=int(time.time() * 1000)))
            await until(lambda: all(any(e.get('type') == 'danmaku' and e.get('clientMessageId') == message_id
                                       for e in state.events) for state in states))
            await until(lambda: any(e.get('type') == 'send_result' and e.get('clientMessageId') == message_id
                                    and e.get('status') == 'accepted' for e in states[sender].events))
            copies = [next(e for e in state.events if e.get('type') == 'danmaku'
                           and e.get('clientMessageId') == message_id) for state in states]
            assert copies[0] == copies[1] and copies[0]['text'] == content
            assert copies[0]['originNodeId'] == ('node-a' if sender == 0 else 'node-b')

        # 使用真实已接收事件，仅篡改版本。大整数低64位为1，仍必须在两节点均记非法且不得重投。
        envelope = dict(schemaVersion=18446744073709551617, event=copies[0])
        command = ['docker', 'exec', 'danmakulab-redis-lab', 'redis-cli', 'PUBLISH',
                   'danmakulab-prepublish:broadcast:v1', json.dumps(envelope, ensure_ascii=False)]
        result = await asyncio.to_thread(subprocess.run, command, capture_output=True, text=True, check=True)
        assert result.stdout.strip() == '2', result.stdout
        for port in (8087, 8088):
            await until(lambda: invalid_version_count(port) == before[port] + 1)
        # 指标说明接收线程已处理坏事件，再留短暂窗口检查没有额外WebSocket广播。
        await asyncio.sleep(.1)
        assert all(len([e for e in state.events if e.get('type') == 'danmaku']) == 2 for state in states)
        assert not any(state.failures for state in states)
        return dict(passed=True, joined=2, accepted=2, received=4,
                    sourceTextLimit=300, receiverTextLimit=200, longTextLength=201,
                    invalidVersionDroppedPerNode=1, duplicates=0,
                    note='真实双节点边界回归，不能代替容量或长时间实验')
    finally:
        for state, peer in zip(states, peers):
            state.closing = True
            peer.close()
        await asyncio.sleep(0)


if __name__ == '__main__':
    report = asyncio.run(verify())
    Path(sys.argv[1]).write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    print(json.dumps(report, ensure_ascii=False))

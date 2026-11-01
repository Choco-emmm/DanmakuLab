"""每轮后归档和可读报告：大位图日志压缩留本机，摘要保存原文件/压缩件SHA-256。

只读取已落盘的原始证据，不启动服务、不重新判定或覆盖客户端的失败结果。
CPU为处理器秒增量/墙钟秒（1.0约占满一核）；所有客户端资源相加后再比较。
"""
import gzip
import hashlib
import json
from pathlib import Path
import shutil
import sys


def read(path):
    return json.loads(path.read_text(encoding='utf-8-sig'))


def put(path, value):
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2)+'\n', encoding='utf-8')


def sha(path):
    with path.open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest().upper()


def resources(raw):
    """输入、排空、关闭分开；没有采样就是缺测，不补成0。Gauge为顺序读取的采样值。"""
    cfg=read(raw/'config.json')
    expected=cfg['hotWorkers']+1
    start=cfg['startAtMs']; end=start+cfg['phaseSeconds']*4000; close=end+5000
    sections={stage:dict(samples=0, completeClientSamples=0, clientWorkingSetPeakBytes=None,
        clientCpuPeakCores=None, serverWorkingSetPeakBytes=None, serverCpuPeakCores=None,
        gaugePeaks={}) for stage in ('preparation','input','drain','closing')}
    previous=None
    for line in (raw/'samples.jsonl').read_text(encoding='utf-8-sig').splitlines():
        sample=json.loads(line);at=sample['atMs']
        stage='preparation' if at<start else 'input' if at<end else 'drain' if at<close else 'closing'
        section=sections[stage];section['samples']+=1
        clients=sample['clients'];complete=len(clients)==expected and all(clients.values())
        def maximum(key, value):
            old=section[key];section[key]=value if old is None else max(old,value)
        if complete:
            section['completeClientSamples']+=1
            maximum('clientWorkingSetPeakBytes',sum(p['workingSetBytes'] for p in clients.values()))
        if sample.get('server'): maximum('serverWorkingSetPeakBytes',sample['server']['workingSetBytes'])
        if previous:
            dt=(at-previous['atMs'])/1000
            prev=previous['clients']
            if dt>0 and complete and set(clients)==set(prev) and all(prev.values()) and all(clients[r]['pid']==prev[r]['pid'] for r in clients):
                maximum('clientCpuPeakCores',sum(clients[r]['cpuSeconds']-prev[r]['cpuSeconds'] for r in clients)/dt)
            if dt>0 and sample.get('server') and previous.get('server'):
                maximum('serverCpuPeakCores',(sample['server']['cpuSeconds']-previous['server']['cpuSeconds'])/dt)
        for metric in sample.get('snapshot',{}).get('metrics',[]):
            if metric['name'].startswith(('lab.outbound.','lab.dispatch.')):
                value=metric['measurements'][0]['value']
                section['gaugePeaks'][metric['name']]=max(section['gaugePeaks'].get(metric['name'],value),value)
        previous=sample
    return sections


def archive(raw, target):
    target.mkdir(exist_ok=True)
    # 原日志包含发送台账、跨来源元数据和每位观众的紧凑位图，压缩只是存储变化。
    # 保留原文件，压缩文件一旦生成不再覆盖；解压内容哈希必须等于原始字节。
    for path in raw.glob('*/k6-stderr.txt'):
        compressed=path.with_suffix('.txt.gz')
        if not compressed.exists():
            with path.open('rb') as src, compressed.open('xb') as dst:
                with gzip.GzipFile(filename='',mode='wb',fileobj=dst,mtime=0) as zipper:
                    shutil.copyfileobj(src,zipper)
        with gzip.open(compressed,'rb') as stream:
            if hashlib.file_digest(stream,'sha256').hexdigest().upper()!=sha(path):
                raise ValueError('Compressed proof does not match original: '+str(path))
    index=[]
    for path in sorted(raw.rglob('*')):
        if path.is_file():
            index.append(dict(path=path.relative_to(raw).as_posix(),bytes=path.stat().st_size,sha256=sha(path)))
    put(target/'evidence-index.json',dict(rawDirectory=str(raw),files=index,
        note='*.txt.gz contains byte-identical original logs, including per-viewer receive bitmaps. Keep the ignored raw directory with this index.'))
    for name in ('audit.json','case-status.json','config.json','cleanup-gauges.json','environment.json',
                 'server-launch.json','run-status.json','source-sha256.json','jar-sha256.json'):
        if (raw/name).exists(): shutil.copyfile(raw/name,target/name)
    if (raw/'config.json').exists() and (raw/'samples.jsonl').exists(): put(target/'resources.json',resources(raw))


def report(output):
    state=read(output/'batch-status.json')
    lines=['# T4 热点工作进程实验', '',
        '此报告不把工具运行结束、投递收齐、正式三对完成和旧问题根因定位混为一项。', '',
        f"- 工具回归通过：{state['testsPassed']}；前置门槛通过：{state['prerequisitesPassed']}。",
        f"- 正式通过：{state['formalCompleted']}/6；RunFormal：{state['runFormal']}。",
        f"- 固定 JAR SHA-256：`{state.get('jarSha256')}`。",
        '- 单实例、local、buffered、8个输出提交线程；同机回环，服务端和全部客户端争用同一台机器。',
        '- 采样延迟为全局观众编号每10人取1人，热点合并毫秒直方图，普通房间P99各自保留。',
        '- CPU口径为占用核心数，客户端内存为同次采样全部k6工作集之和；峰值是采样峰值。', '',
        '| 轮次 | 热点进程 | 通过/证据完整 | 热点实收/应收 | 热点最大发送滞后ms | 热点最大定时器漂移ms | 热点20/100/200 P99 ms | 全客户端输入期峰值MiB |',
        '|---|---:|---|---:|---:|---:|---|---:|']
    for row in state['rows']:
        raw=Path(row['directory']); target=output/row['name'];archive(raw,target)
        audit=read(raw/'audit.json') if (raw/'audit.json').exists() else {}
        hot=audit.get('hot',{}); workers=audit.get('workers',[])
        q='/'.join(str(p['p99']) for p in audit.get('hotLatency',[])[1:]) or '缺测'
        r=read(target/'resources.json') if (target/'resources.json').exists() else {}
        mem=r.get('input',{}).get('clientWorkingSetPeakBytes')
        lines.append(f"| [{row['name']}]({row['name']}/audit.json) | {row['workers']} | {row['passed']}/{row['evidenceComplete']} | "
            f"{hot.get('received','?')}/{hot.get('demand','?')} | {max((w['maxSendLagMs'] for w in workers),default='?')} | "
            f"{max((w['maxTickDriftMs'] for w in workers),default='?')} | {q} | {round(mem/1048576,1) if mem is not None else '缺测'} |")
    lines+=['','## 门槛与未执行项','']
    # 没有失败原因不等于“尚无结论”：门槛为true时应明确写通过。
    # 同时区分负载验收与用户是否选择正式阶段，避免短测报告暗示必须立即跑长测。
    lines += ['- '+reason for reason in state.get('gateReasons',[])] or [
        '- 前置门槛通过。' if state['prerequisitesPassed'] else '- 前置门槛尚未通过；请查看检查及错误记录。']
    if not state['runFormal']: lines+=['- 本批未请求正式长测。']
    if state.get('error'): lines+=['- 编排错误：'+state['error']]
    lines+=['- 未执行：'+', '.join(state.get('notExecuted',[])), '', '## 阅读与复现','',
        '各轮 `audit.json` 保存独立合并对账、热点分位数、普通房间逐阶段P99和错误；`resources.json` 将准备、输入、排空、关闭分开。',
        '`evidence-index.json` 指向原始目录并记录原文件和压缩文件哈希，包含全部进程命令、PID、退出码、采样、发送确认和逐观众位图；不是仅凭服务端send次数证明收齐。', '',
        '```powershell', 'cd D:\\coding\\DanmakuLab',
        'pwsh -NoProfile -File scripts/run/hotspot-workers.ps1', '```', '',
        '结果目录不能复用。默认不带RunFormal只运行全部工具验收和四轮短对照；正式轮遇到失败立即停下，绝不自动重试补齐。', '',
        '本次没有修改Java业务。拆分后若延迟降低，首先说明压测器开销发生变化；没有额外因果证据，不能写成服务端优化、容量翻倍或旧故障已修复。','']
    (output/'report.md').write_text('\n'.join(lines),encoding='utf-8')


if __name__=='__main__': report(Path(sys.argv[1]))

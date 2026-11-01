"""T3/T5批次证据归档与状态报告；只读取原记录，不重分类慢组保护性失败。

原始文件留忽略目录，每个索引记录SHA-256。源码压缩快照与清单并存，方便以后复现。
"""
import argparse
import hashlib
import json
from pathlib import Path
import shutil
from zipfile import ZipFile, ZIP_DEFLATED

ROOT=Path(__file__).resolve().parents[2]
def read(path): return json.loads(path.read_text(encoding='utf-8-sig'))
def put(path,value): path.write_text(json.dumps(value,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
def sha(path):
    with path.open('rb') as stream:return hashlib.file_digest(stream,'sha256').hexdigest().upper()


def freeze(out):
    manifest=read(out/'frozen-source.json')
    archive=ROOT/'.lab-artifacts'/f'{out.name}-source.zip'
    with ZipFile(archive,'x',ZIP_DEFLATED) as zipper:
        for item in manifest:
            path=ROOT/item['path'];assert sha(path)==item['sha256']
            zipper.write(path,path.relative_to(ROOT).as_posix())
    put(out/'source-archive.json',dict(path=str(archive),sha256=sha(archive),files=len(manifest)))


def report(out):
    state=read(out/'batch-status.json')
    lines=['# T3/T5独立重复','',f"前置通过：{state['preflightPassed']}；正式通过：{state['formalPassed']}/6。",'',
        '正常观众固定保留在分母；慢组丢弃/关闭与正常组接收分别报告。T3必须另有服务端背压证据。','',
        '| 轮次 | 场景 | 正式 | 通过 | 正常实收/应收 | P95/P99 ms | 最大发送滞后ms |','|---|---|---|---|---:|---|---:|']
    for row in state['rows']:
        raw=Path(row['directory']);target=out/row['name'];target.mkdir(exist_ok=True)
        index=[dict(path=p.relative_to(raw).as_posix(),bytes=p.stat().st_size,sha256=sha(p)) for p in sorted(raw.rglob('*')) if p.is_file()]
        put(target/'evidence-index.json',dict(directory=str(raw),files=index))
        for src in ('case-status.json','server-launch.json','outer-cleanup.json','command.json','audit-output.txt'):
            if (raw/src).exists():shutil.copyfile(raw/src,target/src)
        load=raw/'load'
        for src in ('config.json','run-status.json','cleanup-gauges.json','counter-delta.json','environment.json',
                    'jar-sha256.json','artifact-identity.json','source-sha256.json','client/summary.json','client/timing.json','python-status.json'):
            if (load/src).exists():
                destination=target/Path(src).name
                shutil.copyfile(load/src,destination)
        if (load/'client/summary.json').exists():
            r=read(load/'client/summary.json');lat=r['normalLatencyMs']
            lines.append(f"| {row['name']} | {row['scenario']} | {row['formal']} | {row['passed']} | "
                f"{r['normalReceived']}/{r['normalExpected']} | {lat['p95']}/{lat['p99']} | {r['maxSendLagMs']:.2f} |")
        else:lines.append(f"| {row['name']} | {row['scenario']} | {row['formal']} | {row['passed']} | 缺少摘要 | 缺测 | 缺测 |")
    if state.get('error'):lines+=['','失败/停止原因：'+state['error']]
    lines+=['','未执行：'+(', '.join(state['notExecuted']) or '无'),'','每轮原始目录与文件SHA-256见evidence-index.json。正式失败后停止，未执行项不补跑。',
        '本批使用local/fair/buffered、8个提交线程和固定发布JAR；不改变Java业务、不自动提交推送。','']
    (out/'report.md').write_text('\n'.join(lines),encoding='utf-8')


if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('directory',type=Path);parser.add_argument('--freeze',action='store_true')
    args=parser.parse_args()
    if args.freeze:freeze(args.directory)
    else:report(args.directory)

import http from 'k6/http';
import { check, sleep } from 'k6';

/**
 * k6 入门示例：反复请求本地服务的健康检查，学习最基本的运行配置与结果判断。
 * 本脚本只测 HTTP 健康接口，不建立 WebSocket，也不能证明弹幕广播容量。
 * 在项目根目录运行：k6 run scripts/load/http-basics.js
 */
export const options = {
  // VU（Virtual User）是执行脚本的虚拟用户，不等于 TCP 连接数或 WebSocket 连接数。
  // 本例有两个 VU；每个 VU 在持续时间内独立、反复执行下方 default 函数。
  vus: 2,
  duration: '10s',
  thresholds: {
    // thresholds 是整轮测试的通过标准；不满足时 k6 以非零退出码结束。
    http_req_failed: ['rate==0'],
    checks: ['rate==1'],
  },
};

// -e BASE_URL=http://127.0.0.1:8081 可切换目标；默认请求当前项目的本地服务。
const baseUrl = __ENV.BASE_URL || 'http://127.0.0.1:8080';

/** 一次迭代：发一个 HTTP 请求 -> 检查响应 -> 等待一秒，然后由 k6 决定是否进入下一轮。 */
export default function () {
  const response = http.get(`${baseUrl}/actuator/health`);

  // check 记录单次断言的成功/失败，本身不会因为 false 就立即结束脚本。
  // 与上面的 checks 阈值结合，才能让“存在失败检查”体现为整轮测试不通过。
  check(response, {
    'HTTP 状态码是 200': (res) => res.status === 200,
    '服务健康状态是 UP': (res) => {
      // 网络错误或非 JSON 响应也转换成失败检查，便于初学者读懂错误统计。
      try {
        return res.json('status') === 'UP';
      } catch {
        return false;
      }
    },
  });

  // 给每个 VU 留一秒间隔。实际循环还包含请求耗时，因此不能把 2 VU 等同于严格 2 次/秒。
  sleep(1);
}

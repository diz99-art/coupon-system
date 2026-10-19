// 선착순 쿠폰 부하 테스트. USERS명이 동시에 한 번씩 발급을 요청한다.
// 실행: k6 run -e BASE_URL=http://localhost:8090 -e COUPON_ID=1 -e METHOD=REDIS_LUA -e USERS=1000 bench/issue.js
import http from 'k6/http';
import exec from 'k6/execution';
import { Counter } from 'k6/metrics';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8090';
const COUPON_ID = __ENV.COUPON_ID;
const METHOD = __ENV.METHOD || 'REDIS_LUA';
const USERS = parseInt(__ENV.USERS || '1000', 10);
const OUTPUT = __ENV.OUTPUT || 'bench/results/last.json';

export const options = {
  scenarios: {
    // VU(가상 사용자) USERS명을 미리 만들어 두고, 한 명이 한 번씩 요청한다.
    burst: { executor: 'shared-iterations', vus: USERS, iterations: USERS, maxDuration: '5m' },
  },
  summaryTrendStats: ['avg', 'p(95)', 'max'],
};

const issued = new Counter('issued');      // 200: 발급 성공
const rejected = new Counter('rejected');  // 409: 매진·중복 (정상적인 거절)
const failed = new Counter('failed');      // 그 밖의 응답: 오류
// 어느 서버가 응답했는지 센다 (응답 헤더 X-Server-Id). k6 지표는 시작 전에 선언해야 한다.
const servedBy = { app1: new Counter('server_app1'), app2: new Counter('server_app2') };
const servedByOther = new Counter('server_other');

export default function () {
  const userId = exec.scenario.iterationInTest + 1;
  const res = http.post(
    `${BASE_URL}/bench/coupons/${COUPON_ID}/issue?method=${METHOD}`,
    JSON.stringify({ userId }),
    { headers: { 'Content-Type': 'application/json' }, timeout: '120s' },
  );
  if (res.status === 200) issued.add(1);
  else if (res.status === 409) rejected.add(1);
  else failed.add(1);

  (servedBy[res.headers['X-Server-Id']] || servedByOther).add(1);
}

export function handleSummary(data) {
  const m = data.metrics;
  const count = (name) => (m[name] ? m[name].values.count : 0);
  const servers = Object.keys(m).filter((k) => k.startsWith('server_'))
    .reduce((acc, k) => ({ ...acc, [k.substring(7)]: m[k].values.count }), {});
  const result = {
    method: METHOD,
    users: USERS,
    requests: count('http_reqs'),
    tps: m.http_reqs.values.rate,
    avgMs: m.http_req_duration.values.avg,
    p95Ms: m.http_req_duration.values['p(95)'],
    maxMs: m.http_req_duration.values.max,
    durationMs: data.state.testRunDurationMs,
    issued: count('issued'),
    rejected: count('rejected'),
    failed: count('failed'),
    servers,
  };
  return {
    [OUTPUT]: JSON.stringify(result, null, 2),
    stdout: `${METHOD} users=${USERS} tps=${result.tps.toFixed(0)} avg=${result.avgMs.toFixed(0)}ms `
      + `p95=${result.p95Ms.toFixed(0)}ms issued=${result.issued} failed=${result.failed} servers=${JSON.stringify(servers)}\n`,
  };
}

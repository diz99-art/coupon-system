// 실제 발급 API(토큰 필요) 부하 테스트. USERS명이 각자 자기 토큰으로 COUPONS장의 쿠폰을 차례로 발급받는다.
// 시험 데이터 규칙 (platform-infra의 docs/test-data.md): 계정은 k6-<번호>@loadtest.invalid, 쿠폰 이름은 [LOAD]로 시작
//   → 끝난 뒤 platform-infra의 scripts/cleanup-test-data.sh 로 한 번에 지운다
// Gateway(Traefik) 요청 제한을 지키는 "정상 사용자" 부하다:
//   - 가입·로그인은 IP당 분당 60회 → 429가 오면 Retry-After만큼 기다렸다 다시 보낸다 (setup이 몇 분 걸린다)
//   - 발급은 토큰당 초당 5회 → 사용자마다 PACE초(기본 0.25) 간격으로 보낸다
// 실행 (k3s 클러스터):
//   ADMIN_TOKEN=$(bench/token.sh admin@platform.local <비밀번호>) k6 run -e ADMIN_TOKEN=$ADMIN_TOKEN -e USERS=100 -e COUPONS=50 bench/issue-auth.js
// (bench/issue.js는 bench 프로필의 비교 API용. 운영에 없는 측정 도구라 userId를 직접 넘긴다)
import http from 'k6/http';
import exec from 'k6/execution';
import { sleep } from 'k6';
import { Counter } from 'k6/metrics';

const BASE_URL = __ENV.BASE_URL || 'http://192.168.252.2';
const AUTH_URL = __ENV.AUTH_URL || `${BASE_URL}/auth`;
const USERS = parseInt(__ENV.USERS || '100', 10);
const COUPONS = parseInt(__ENV.COUPONS || '50', 10);
const PACE = parseFloat(__ENV.PACE || '0.25');
const PASSWORD = 'Bench!pass1';
const JSON_HEADERS = { 'Content-Type': 'application/json' };

export const options = {
  setupTimeout: '15m',
  scenarios: {
    // VU 하나 = 사용자 하나. 각자 COUPONS번 반복(쿠폰마다 한 번 발급)
    users: { executor: 'per-vu-iterations', vus: USERS, iterations: COUPONS, maxDuration: '15m' },
  },
  thresholds: { failed: ['count==0'], limited: ['count==0'] },
  summaryTrendStats: ['avg', 'p(95)', 'p(99)', 'max'],
};

const issued = new Counter('issued');      // 202: 발급 성공
const rejected = new Counter('rejected');  // 409: 매진·중복 (정상적인 거절)
const limited = new Counter('limited');    // 429: Gateway 요청 제한 (정상 사용자라면 0이어야 한다)
const failed = new Counter('failed');      // 그 밖의 응답 (401 포함): 오류

// 요청을 10개씩 동시에 보내고, 429(요청 제한)를 받은 것은 Retry-After만큼 기다렸다 다시 보낸다
function paced(requests, okStatuses) {
  const out = new Array(requests.length);
  let pending = requests.map((r, i) => i);
  while (pending.length > 0) {
    const chunk = pending.slice(0, 10);
    const res = http.batch(chunk.map((i) => requests[i]));
    const retry = [];
    let wait = 0;
    res.forEach((r, k) => {
      if (r.status === 429) { retry.push(chunk[k]); wait = Math.max(wait, parseFloat(r.headers['Retry-After'] || '1')); }
      else if (!okStatuses.includes(r.status)) throw new Error(`${requests[chunk[k]][1]} 실패: ${r.status} ${r.body}`);
      else out[chunk[k]] = r;
    });
    if (wait > 0) sleep(wait);
    pending = retry.concat(pending.slice(10));
  }
  return out;
}

export function setup() {
  if (!__ENV.ADMIN_TOKEN) throw new Error('ADMIN_TOKEN이 필요합니다 (쿠폰 생성은 ADMIN만)');
  const emails = Array.from({ length: USERS }, (_, i) => `k6-${i + 1}@loadtest.invalid`);
  paced(emails.map((email) => ['POST', `${AUTH_URL}/signup`,
    JSON.stringify({ email, password: PASSWORD, name: '부하' }), { headers: JSON_HEADERS }]), [201, 409]);
  const tokens = paced(emails.map((email) => ['POST', `${AUTH_URL}/login`,
    JSON.stringify({ email, password: PASSWORD }), { headers: JSON_HEADERS }]), [200])
    .map((res) => res.json('accessToken'));

  const now = Date.now() + 9 * 3600e3;   // 서버는 서울 시각
  const couponIds = [];
  for (let c = 0; c < COUPONS; c++) {
    const res = http.post(`${BASE_URL}/coupons`, JSON.stringify({
      name: `[LOAD] k6 ${c + 1}`, totalQuantity: USERS,
      issueStartAt: new Date(now - 60e3).toISOString().slice(0, 19),
      issueEndAt: new Date(now + 86400e3).toISOString().slice(0, 19),
    }), { headers: { ...JSON_HEADERS, Authorization: `Bearer ${__ENV.ADMIN_TOKEN}` } });
    if (res.status !== 201) throw new Error(`쿠폰 생성 실패: ${res.status} ${res.body}`);
    couponIds.push(res.json('id'));
  }
  return { tokens, couponIds };
}

export default function (data) {
  const token = data.tokens[exec.vu.idInTest - 1];
  const couponId = data.couponIds[exec.vu.iterationInScenario];
  const res = http.post(`${BASE_URL}/coupons/${couponId}/issue`, null,
    { headers: { Authorization: `Bearer ${token}` }, timeout: '60s' });
  if (res.status === 202) issued.add(1);
  else if (res.status === 409) rejected.add(1);
  else if (res.status === 429) limited.add(1);
  else failed.add(1);
  sleep(PACE);
}

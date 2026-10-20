// 실제 발급 API(토큰 필요) 부하 테스트. USERS명이 각자 자기 토큰으로 한 번씩 발급을 요청한다.
// setup: 인증 서버에 USERS명을 가입시키고(이미 있으면 409, 그대로 진행) 로그인해 토큰을 받는다.
// 실행 (k3s 클러스터):
//   k6 run -e COUPON_ID=1 -e USERS=500 bench/issue-auth.js
//   쿠폰을 새로 만들려면 COUPON_ID 대신 ADMIN_TOKEN=$(bench/token.sh admin@platform.local <비밀번호>) 와 QUANTITY를 준다
// (bench/issue.js는 bench 프로필의 비교 API용. 운영에 없는 측정 도구라 userId를 직접 넘긴다)
import http from 'k6/http';
import exec from 'k6/execution';
import { Counter } from 'k6/metrics';

const BASE_URL = __ENV.BASE_URL || 'http://192.168.252.2';
const AUTH_URL = __ENV.AUTH_URL || `${BASE_URL}/auth`;
const USERS = parseInt(__ENV.USERS || '500', 10);
const PASSWORD = 'Bench!pass1';
const JSON_HEADERS = { 'Content-Type': 'application/json' };

export const options = {
  setupTimeout: '10m',
  scenarios: {
    burst: { executor: 'shared-iterations', vus: Math.min(USERS, 200), iterations: USERS, maxDuration: '5m' },
  },
  summaryTrendStats: ['avg', 'p(95)', 'max'],
};

const issued = new Counter('issued');      // 202: 발급 성공
const rejected = new Counter('rejected');  // 409: 매진·중복 (정상적인 거절)
const failed = new Counter('failed');      // 그 밖의 응답 (401 포함): 오류

// 여러 요청을 나눠서 동시에 보낸다 (BCrypt가 느려 하나씩 하면 오래 걸린다)
function batched(requests, size = 50) {
  const out = [];
  for (let i = 0; i < requests.length; i += size) out.push(...http.batch(requests.slice(i, i + size)));
  return out;
}

export function setup() {
  const emails = Array.from({ length: USERS }, (_, i) => `bench${i + 1}@bench.local`);
  batched(emails.map((email) => ['POST', `${AUTH_URL}/signup`,
    JSON.stringify({ email, password: PASSWORD, name: '부하' }), { headers: JSON_HEADERS }]));
  const tokens = batched(emails.map((email) => ['POST', `${AUTH_URL}/login`,
    JSON.stringify({ email, password: PASSWORD }), { headers: JSON_HEADERS }]))
    .map((res, i) => {
      if (res.status !== 200) throw new Error(`로그인 실패 ${emails[i]}: ${res.status} ${res.body}`);
      return res.json('accessToken');
    });

  let couponId = __ENV.COUPON_ID;
  if (!couponId) {
    if (!__ENV.ADMIN_TOKEN) throw new Error('COUPON_ID 또는 ADMIN_TOKEN이 필요합니다');
    const now = new Date();
    const res = http.post(`${BASE_URL}/coupons`, JSON.stringify({
      name: 'k6 부하', totalQuantity: parseInt(__ENV.QUANTITY || String(USERS), 10),
      issueStartAt: new Date(now.getTime() - 60e3 + 9 * 3600e3).toISOString().slice(0, 19),   // 서버는 서울 시각
      issueEndAt: new Date(now.getTime() + 86400e3 + 9 * 3600e3).toISOString().slice(0, 19),
    }), { headers: { ...JSON_HEADERS, Authorization: `Bearer ${__ENV.ADMIN_TOKEN}` } });
    if (res.status !== 201) throw new Error(`쿠폰 생성 실패: ${res.status} ${res.body}`);
    couponId = res.json('id');
  }
  return { tokens, couponId };
}

export default function (data) {
  const token = data.tokens[exec.scenario.iterationInTest];
  const res = http.post(`${BASE_URL}/coupons/${data.couponId}/issue`, null,
    { headers: { Authorization: `Bearer ${token}` }, timeout: '60s' });
  if (res.status === 202) issued.add(1);
  else if (res.status === 409) rejected.add(1);
  else failed.add(1);
}

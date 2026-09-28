// 오픈 순간 접수 급증: 초당 RATE 건씩 DURATION 동안(기본 10초 5,000건). 회원마다 한 번씩 접수하므로 토큰이 건수만큼 필요하다.
// 실행: k6 run -e BASE_URL=… -e PRODUCT_ID=… -e OPTION_ID=… -e TICKET_SECRET=… -e TOKENS_FILE=tokens.csv accept-spike.js
// 끝나면 invariant-check.sql 로 유실 · 중복을 확인한다. 회차 잠금 대기는 /actuator/prometheus 의 preorder_campaign_lock_wait_seconds 로 본다.
import http from 'k6/http';
import { check, fail } from 'k6';
import exec from 'k6/execution';
import { SharedArray } from 'k6/data';
import { Counter } from 'k6/metrics';
import { issueAdmissionTicket } from './admission-ticket.js';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8083';
const PRODUCT_ID = Number(__ENV.PRODUCT_ID);
const OPTION_ID = Number(__ENV.OPTION_ID);
const TICKET_SECRET = __ENV.TICKET_SECRET;
const RATE = Number(__ENV.RATE || 500);
const DURATION_SECONDS = Number(__ENV.DURATION_SECONDS || 10);
const RUN_ID = __ENV.RUN_ID || `${Date.now()}`;

/** customerId,accessToken 한 줄에 한 회원. 회원 서비스가 발급한 토큰을 미리 받아 둔다. */
const members = new SharedArray('members', () => open(__ENV.TOKENS_FILE)
    .split('\n')
    .map((line) => line.trim())
    .filter((line) => line.length > 0 && !line.startsWith('#'))
    .map((line) => {
      const [customerId, accessToken] = line.split(',');
      return { customerId: Number(customerId), accessToken };
    }));

const acceptErrors = new Counter('accept_errors');

export const options = {
  scenarios: {
    spike: {
      executor: 'constant-arrival-rate',
      rate: RATE,
      timeUnit: '1s',
      duration: `${DURATION_SECONDS}s`,
      preAllocatedVUs: RATE,
      maxVUs: RATE * 4,
    },
  },
  thresholds: {
    'checks{name:accept}': ['rate>0.99'],
    'http_req_duration{name:accept}': ['p(95)<300', 'p(99)<500'],
    // VU 가 모자라 시작하지 못한 접수도 실패로 본다
    dropped_iterations: ['count==0'],
  },
};

export function setup() {
  const total = RATE * DURATION_SECONDS;
  if (!PRODUCT_ID || !OPTION_ID || !TICKET_SECRET) {
    fail('PRODUCT_ID · OPTION_ID · TICKET_SECRET 가 필요하다');
  }
  // 회원 토큰이 실리므로 로컬이 아니면 HTTPS 만 허용한다
  if (!/^https:\/\//.test(BASE_URL) && !/^http:\/\/(localhost|127\.0\.0\.1)(:\d+)?(\/|$)/.test(BASE_URL)) {
    fail(`BASE_URL 은 https 여야 한다(로컬만 http 허용): ${BASE_URL}`);
  }
  if (members.length < total) {
    fail(`회원 토큰이 ${total} 개 필요한데 ${members.length} 개다 — 한 회원은 한 상품에 한 번만 접수된다`);
  }
  const customerIds = new Set();
  for (let i = 0; i < total; i++) {
    customerIds.add(members[i].customerId);
  }
  if (customerIds.size < total) {
    fail(`앞 ${total} 줄에 같은 customerId 가 ${total - customerIds.size} 개 겹친다`);
  }
}

export default function () {
  const iteration = exec.scenario.iterationInTest;
  const member = members[iteration];
  if (!member) {
    fail(`회원 토큰이 모자라다: ${iteration} 번째 접수`);
  }
  const ticket = issueAdmissionTicket(TICKET_SECRET, PRODUCT_ID, member.customerId, Math.floor(Date.now() / 1000));

  const response = http.post(`${BASE_URL}/api/v1/preorders?productId=${PRODUCT_ID}`,
      JSON.stringify({ productId: PRODUCT_ID, optionId: OPTION_ID }), {
        headers: {
          'Content-Type': 'application/json',
          'X-Session-Token': member.accessToken,
          'Idempotency-Key': `load-${RUN_ID}-${iteration}`,
          'X-Admission-Ticket': ticket,
        },
        tags: { name: 'accept' },
      });

  const accepted = check(response, { '202 접수': (r) => r.status === 202 }, { name: 'accept' });
  if (!accepted) {
    acceptErrors.add(1, { status: `${response.status}`, code: errorCode(response) });
  }
}

function errorCode(response) {
  try {
    return response.json('error.code') || 'NONE';
  } catch (e) {
    return 'UNPARSEABLE';
  }
}

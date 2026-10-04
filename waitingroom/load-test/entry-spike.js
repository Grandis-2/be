// 오픈 순간 진입 급증: 초당 RATE 명이 DURATION 동안 예약 페이지에 들어온다(기본 10초 5,000명).
// 줄에 서면 응답의 Retry-After 대로 순서를 조회하다 입장권을 받는다. 한 회원은 한 번만 진입한다.
// 실행: k6 run -e BASE_URLS=http://localhost:8085,http://localhost:8086 -e PRODUCT_ID=… -e TOKENS_FILE=build/tokens.csv entry-spike.js
import http from 'k6/http';
import { check, fail, sleep } from 'k6';
import exec from 'k6/execution';
import { SharedArray } from 'k6/data';
import { Counter, Trend } from 'k6/metrics';

const BASE_URLS = (__ENV.BASE_URLS || 'http://localhost:8085').split(',');
const PRODUCT_ID = __ENV.PRODUCT_ID;
const RATE = Number(__ENV.RATE || 500);
const DURATION_SECONDS = Number(__ENV.DURATION_SECONDS || 10);
/** 줄에서 이만큼 기다려도 입장하지 못하면 포기로 센다. */
const MAX_WAIT_SECONDS = Number(__ENV.MAX_WAIT_SECONDS || 300);

const members = new SharedArray('members', () => open(__ENV.TOKENS_FILE)
    .split('\n')
    .map((line) => line.trim())
    .filter((line) => line.length > 0)
    .map((line) => {
      const [customerId, accessToken] = line.split(',');
      return { customerId, accessToken };
    }));

/** 진입하자마자 입장권을 받았다(한산 통과 · 이미 입장). */
const admittedAtEntry = new Counter('admitted_at_entry');
/** 줄에 섰다가 조회로 입장권을 받았다. */
const admittedFromQueue = new Counter('admitted_from_queue');
const queued = new Counter('queued');
const rejected = new Counter('rejected');
const lostPlace = new Counter('lost_place');
const gaveUp = new Counter('gave_up');
/** 같은 회원의 순서가 뒤로 갔다. 하나라도 있으면 대기열의 약속이 깨진 것이다. */
const positionRegressions = new Counter('position_regressions');
/** 진입부터 입장권을 받기까지(초). */
const timeToAdmission = new Trend('time_to_admission_seconds');
const polls = new Counter('status_polls');

export const options = {
  scenarios: {
    spike: {
      executor: 'constant-arrival-rate',
      rate: RATE,
      timeUnit: '1s',
      duration: `${DURATION_SECONDS}s`,
      // 줄에 선 사람은 입장까지 VU 를 붙든다
      preAllocatedVUs: RATE * DURATION_SECONDS,
      maxVUs: RATE * DURATION_SECONDS,
      // 진입이 끝난 뒤에도 줄에 선 사람이 입장할 때까지 기다린다
      gracefulStop: `${MAX_WAIT_SECONDS + 30}s`,
    },
  },
  thresholds: {
    position_regressions: ['count==0'],
    // 최대 대기 시간을 정하지 않은 이 시나리오에서는 줄이 차서 거절(429)될 일이 없다 — 하나라도 있으면 실패다
    rejected: ['count==0'],
    'checks{name:entry}': ['rate>0.99'],
    dropped_iterations: ['count==0'],
  },
};

export function setup() {
  if (!PRODUCT_ID) {
    fail('PRODUCT_ID 가 필요하다');
  }
  const total = RATE * DURATION_SECONDS;
  if (members.length < total) {
    fail(`회원 토큰이 ${total} 개 필요한데 ${members.length} 개다 — 한 회원은 한 번만 진입한다`);
  }
}

const data = (response) => {
  try {
    return response.json().data;
  } catch (e) {
    return null;
  }
};

const retryAfter = (response) => Math.max(1, Number(response.headers['Retry-After'] || 1));

export default function () {
  const iteration = exec.scenario.iterationInTest;
  const member = members[iteration];
  const base = BASE_URLS[iteration % BASE_URLS.length];
  const auth = { Authorization: `Bearer ${member.accessToken}` };
  const enteredAt = Date.now();

  const entry = http.post(`${base}/api/v1/preorders/queue?productId=${PRODUCT_ID}`, null,
      { headers: auth, tags: { name: 'entry' } });
  const entered = data(entry);
  check(entry, { 'entry answered': (r) => r.status === 200 || r.status === 202 }, { name: 'entry' });
  if (entry.status === 200 && entered && entered.status === 'ADMITTED') {
    admittedAtEntry.add(1);
    timeToAdmission.add(0);
    return;
  }
  if (entry.status !== 202 || !entered || !entered.queueToken) {
    rejected.add(1, { status: String(entry.status) });
    return;
  }
  queued.add(1);

  let position = entered.position;
  let wait = retryAfter(entry);
  while ((Date.now() - enteredAt) / 1000 < MAX_WAIT_SECONDS) {
    sleep(wait);
    const status = http.get(`${base}/api/v1/preorders/queue?productId=${PRODUCT_ID}`,
        { headers: { ...auth, 'Queue-Token': entered.queueToken }, tags: { name: 'status' } });
    polls.add(1);
    const view = data(status);
    if (!view) {
      wait = retryAfter(status);
      continue;
    }
    if (view.status === 'ADMITTED') {
      admittedFromQueue.add(1);
      timeToAdmission.add((Date.now() - enteredAt) / 1000);
      return;
    }
    if (view.status === 'CLOSED') {
      lostPlace.add(1, { reason: view.reason });
      return;
    }
    if (view.position > position) {
      positionRegressions.add(1);
    }
    position = view.position;
    wait = retryAfter(status);
  }
  gaveUp.add(1);
}

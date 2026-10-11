// AOT 학습 workload 공통. 서비스 주소 · 토큰 · 응답 확인. train.sh 가 /work/data.json 을 넣어 준다.
import http from 'k6/http';
import { check, sleep } from 'k6';
import exec from 'k6/execution';

export const data = JSON.parse(open('/work/data.json'));

// 서비스 목록 · 포트는 train.sh 가 넘긴다(WORKLOAD_SERVICES · SERVER_PORT). 학습 컨테이너는 서비스마다 같은 포트로 뜬다
export const URL = {};
for (const s of data.services) {
  URL[s] = `http://${s}:${data.servicePort}`;
}

export function uuid() {
  // v4 형식. 멱등 키 · 이벤트 id 용
  return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, (c) => {
    const r = (Math.random() * 16) | 0;
    return (c === 'x' ? r : (r & 0x3) | 0x8).toString(16);
  });
}

export function headers(token, extra) {
  const h = { 'Content-Type': 'application/json' };
  if (token) {
    h.Authorization = `Bearer ${token}`;
  }
  return Object.assign(h, extra || {});
}

// 요청 하나. expected 는 기대하는 상태 코드(숫자 또는 배열). 어긋나면 checks 실패로 남고, 실행의 성패는 각 스크립트의 임계값이 정한다.
// 503(잠시 후 다시)은 클라이언트처럼 1초 뒤 두 번까지 다시 보낸다. 막 뜬 서비스는 서비스 간 첫 호출이 읽기 시간 제한(1초)을
// 넘기기 쉽다. 같은 본문 · 헤더(멱등 키 포함)로 다시 보내므로 결과는 재요청과 같다. 그래도 503 이면 실패로 남긴다.
export function call(method, url, body, params, expected, name) {
  const options = Object.assign({ tags: { name: name || url } }, params);
  options.headers = Object.assign({ 'Content-Type': 'application/json' }, options.headers);
  const want = Array.isArray(expected) ? expected : [expected];
  const payload = body === null || body === undefined ? null : JSON.stringify(body);
  let res = http.request(method, url, payload, options);
  for (let retry = 0; retry < 2 && res.status === 503 && !want.includes(503); retry++) {
    sleep(1);
    res = http.request(method, url, payload, options);
  }
  const ok = check(res, { [`${name || url} → ${want.join('|')}`]: (r) => want.includes(r.status) });
  if (!ok) {
    // 시나리오가 코드와 어긋났을 때 CI 로그만으로 원인을 볼 수 있게 남긴다(학습용 데이터라 본문에 비밀값이 없다)
    console.warn(`${method} ${url} → ${res.status} ${String(res.body || res.error).slice(0, 300)}`);
  }
  return res;
}

export function dataOf(res) {
  try {
    return res.json('data');
  } catch (e) {
    return null;
  }
}

// 준비 단계에서 꼭 있어야 하는 값. 없으면 이후가 무의미하므로 실행 전체를 실패로 멈춘다.
// fail() 은 그 반복만 끝내고 종료 코드는 0 이라 쓰지 않는다(k6 1.7 실측).
export function must(value, what) {
  if (value === null || value === undefined) {
    exec.test.abort(`학습 준비 실패: ${what}`);
  }
  return value;
}

export function adminLogin() {
  const res = call('POST', `${URL.member}/api/v1/admin/session`,
    { username: 'admin', password: data.adminPassword }, {}, 200, 'member admin login');
  return must(dataOf(res) && dataOf(res).sessionToken, '관리자 로그인');
}

// 반복 본문의 예외(응답 모양이 바뀌어 생기는 TypeError 등)를 실패 check 로 남긴다. 잡지 않으면 그 반복만 끊기고
// 실행은 초록으로 끝난다(k6 1.7 실측) — 그 경로가 학습에서 빠진 것을 모르게 된다.
export function guarded(name, body) {
  try {
    body();
  } catch (e) {
    console.warn(`${name}: ${e}`);
    check(null, { [`${name} 예외 없음`]: () => false });
  }
}

export function user(i) {
  return data.users[i % data.users.length];
}

// 서명이 게이트웨이 · preorder 와 같은지 공통 테스트 벡터로 확인한다. 실행: k6 run admission-ticket.check.js
import { check, fail } from 'k6';
import { issueAdmissionTicket } from './admission-ticket.js';

const SECRET = 'nova-test-current-secret-0123456789';
const ISSUED_AT = Date.parse('2026-10-01T01:00:07Z') / 1000;

export const options = { iterations: 1, vus: 1, thresholds: { checks: ['rate==1'] } };

export default function () {
  const ok = check(null, {
    '101/1024': () => issueAdmissionTicket(SECRET, 101, 1024, ISSUED_AT)
        === 'et_MTAxHzEwMjQfMTc5MDgxNjUyMA.eLyT_jlZvbtd8xkwWxcyH2fvoyaZs0cXeXQbA8E-OKo',
    '같은 창(20초 뒤)': () => issueAdmissionTicket(SECRET, 101, 1024, ISSUED_AT + 20)
        === 'et_MTAxHzEwMjQfMTc5MDgxNjUyMA.eLyT_jlZvbtd8xkwWxcyH2fvoyaZs0cXeXQbA8E-OKo',
    '202/77': () => issueAdmissionTicket(SECRET, 202, 77, ISSUED_AT)
        === 'et_MjAyHzc3HzE3OTA4MTY1MjA.z6M2CLHz31DBoddp3bVbXUdfoQofqEWhz0Mee728ztA',
  });
  if (!ok) {
    fail('입장권 서명이 벡터와 다르다');
  }
}

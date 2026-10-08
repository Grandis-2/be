// 서명이 게이트웨이 · preorder 와 같은지 공통 테스트 벡터로 확인한다. 실행: k6 run admission-ticket.check.js
import { check, fail } from 'k6';
import { issueAdmissionTicket } from './admission-ticket.js';

const SECRET = 'nova-test-current-secret-0123456789';
const ISSUED_AT = Date.parse('2026-10-01T01:00:07Z') / 1000;
const PRODUCT = '00000000-0000-7000-8000-000000000101';
const CUSTOMER = '00000000-0000-7000-8000-000000001024';
const OTHER_PRODUCT = '00000000-0000-7000-8000-000000000202';
const OTHER_CUSTOMER = '00000000-0000-7000-8000-000000000077';
const VECTOR_CURRENT = 'et_MDAwMDAwMDAtMDAwMC03MDAwLTgwMDAtMDAwMDAwMDAwMTAxHzAwMDAwMDAwLTAwMDAtNzAwMC04'
    + 'MDAwLTAwMDAwMDAwMTAyNB8xNzkwODE2NTIw.QDp7_sRDfjNKhq8Hw2y0yzpizrzNBYaXSe5Lhy-nh1M';
const VECTOR_OTHER = 'et_MDAwMDAwMDAtMDAwMC03MDAwLTgwMDAtMDAwMDAwMDAwMjAyHzAwMDAwMDAwLTAwMDAtNzAw'
    + 'MC04MDAwLTAwMDAwMDAwMDA3Nx8xNzkwODE2NTIw.5UT5cldXa-INLn6aW4qgQtoFq0HD-dYESzzSzgtsl44';

export const options = { iterations: 1, vus: 1, thresholds: { checks: ['rate==1'] } };

export default function () {
  const ok = check(null, {
    '상품/회원': () => issueAdmissionTicket(SECRET, PRODUCT, CUSTOMER, ISSUED_AT) === VECTOR_CURRENT,
    '같은 창(20초 뒤)': () => issueAdmissionTicket(SECRET, PRODUCT, CUSTOMER, ISSUED_AT + 20) === VECTOR_CURRENT,
    '다른 상품/회원': () => issueAdmissionTicket(SECRET, OTHER_PRODUCT, OTHER_CUSTOMER, ISSUED_AT) === VECTOR_OTHER,
  });
  if (!ok) {
    fail('입장권 서명이 벡터와 다르다');
  }
}

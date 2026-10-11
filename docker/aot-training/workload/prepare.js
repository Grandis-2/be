// AOT 학습 1부 — 상품 등록부터 예약 접수까지. 한 번만 돈다(VU 1 · 반복 1).
// 등록 이벤트가 preorder(회차 · 배송 차수) · order(재고) · waitingroom(일정)으로 흘러가고, 접수는 대기열 경유 · 직접 · 관리자를 섞는다.
// 끝나면 train.sh 가 worker 대신 동기화 작업을 성공으로 넘겨 예약을 REGISTERED 로 만들고 2부(steady.js)를 돌린다.
import { sleep } from 'k6';
import exec from 'k6/execution';
import { issueAdmissionTicket } from '/repo/preorder/load-test/admission-ticket.js';
import { URL, adminLogin, call, data, dataOf, headers, must, user, uuid } from './lib.js';

export const options = {
  vus: 1,
  iterations: 1,
  // 준비가 하나라도 어긋나면 2부가 무의미하다 — 시나리오가 코드와 어긋난 것이므로 실패시킨다
  thresholds: { checks: ['rate==1'] },
};

const DAY = 24 * 3600 * 1000;

function iso(ms) {
  return new Date(ms).toISOString();
}

function date(ms) {
  return iso(ms).slice(0, 10);
}

function register(admin, key, body, expected) {
  return call('POST', `${URL.catalog}/api/v1/admin/products`, body,
    { headers: headers(admin, { 'Idempotency-Key': key }) }, expected, 'catalog register');
}

function preorderBody(title, opensAt, axes) {
  return Object.assign({
    categoryId: data.categoryId, saleMode: 'PREORDER', title, visible: true, basePrice: 1200000,
    description: 'AOT 학습용 사전예약 상품', tags: 'aot,학습',
    campaign: { opensAt: iso(opensAt), closesAt: iso(opensAt + 2 * DAY) },
    shipmentBatches: [
      { batchNumber: 1, positionFrom: 1, positionTo: 10, estimatedShipStart: date(opensAt + 20 * DAY), estimatedShipEnd: date(opensAt + 27 * DAY) },
      { batchNumber: 2, positionFrom: 11, positionTo: null, estimatedShipStart: date(opensAt + 30 * DAY), estimatedShipEnd: date(opensAt + 40 * DAY) },
    ],
  }, axes ? { optionAxes: axes } : {});
}

const STORAGE = [{ key: 'storage', label: '용량', values: [{ value: '256GB' }, { value: '512GB', surcharge: 150000 }] }];

function waitCompleted(admin, key) {
  for (let i = 0; i < 60; i++) {
    const res = call('GET', `${URL.catalog}/api/v1/admin/products/registrations/${key}`, null,
      { headers: headers(admin) }, 200, 'catalog registration status');
    if (dataOf(res) && dataOf(res).completed) {
      return dataOf(res).productId;
    }
    sleep(1);
  }
  return must(null, `등록 완료 대기(${key})`);
}

function variants(admin, productId) {
  const res = call('GET', `${URL.catalog}/api/v1/admin/products/${productId}`, null, { headers: headers(admin) },
    200, 'catalog admin product');
  return must(dataOf(res) && dataOf(res).product.variants.map((v) => v.variantId), `옵션(${productId})`);
}

// 대기열 → 입장권 → 게이트웨이로 접수. 대기(202)면 Queue-Token 으로 입장할 때까지 폴링한다.
function acceptThroughQueue(u, productId, optionId) {
  const auth = headers(u.token);
  let ticket = null;
  let res = call('POST', `${URL.waitingroom}/api/v1/preorders/queue?productId=${productId}`, null, { headers: auth },
    [200, 202], 'waitingroom enter');
  for (let i = 0; i < 60 && !ticket; i++) {
    const body = dataOf(res);
    if (body && body.status === 'ADMITTED') {
      ticket = body.admissionTicket;
    } else {
      sleep(Math.min(Number(res.headers['Retry-After'] || 1), 2));
      res = call('GET', `${URL.waitingroom}/api/v1/preorders/queue?productId=${productId}`, null,
        { headers: headers(u.token, { 'Queue-Token': must(body && body.queueToken, '대기 토큰') }) }, 200, 'waitingroom poll');
    }
  }
  must(ticket, `입장권(${u.customerId})`);
  call('POST', `${URL.waitingroom}/api/v1/preorders?productId=${productId}`, { productId, optionId },
    { headers: headers(u.token, { 'Idempotency-Key': uuid(), 'X-Admission-Ticket': ticket }) }, 202, 'gateway accept');
}

function acceptDirect(u, productId, optionId, key, expected) {
  const ticket = issueAdmissionTicket(data.waitingSecret, productId, u.customerId, Math.floor(Date.now() / 1000));
  return call('POST', `${URL.preorder}/api/v1/preorders?productId=${productId}`, { productId, optionId },
    { headers: headers(u.token, { 'Idempotency-Key': key, 'X-Admission-Ticket': ticket }) }, expected, 'preorder accept');
}

export default function () {
  // 준비 중의 예외는 실행 전체를 실패로 멈춘다 — 끊긴 채 끝나면 원인이 뒤의 "등록 작업 없음"으로 엉뚱하게 드러난다
  try {
    prepare();
  } catch (e) {
    exec.test.abort(`학습 준비 실패: ${e}`);
  }
}

function prepare() {
  const admin = adminLogin();
  call('GET', `${URL.member}/api/v1/session`, null, { headers: headers(admin) }, 200, 'member session');

  // ── 상품 등록: 사전예약 둘(옵션 없음 · 용량 축), 일반 둘. 같은 키 재요청 · 잘못된 본문도 섞는다
  const opensAt = Date.now() + data.openLeadSeconds * 1000;
  const keys = { p1: uuid(), p2: uuid(), s1: uuid(), s2: uuid() };
  register(admin, keys.p1, preorderBody('AOT 학습 폰', opensAt), 201);
  register(admin, keys.p2, preorderBody('AOT 학습 폰 프로', opensAt, STORAGE), 201);
  register(admin, keys.s1, { categoryId: data.categoryId, saleMode: 'IN_STOCK', title: 'AOT 학습 케이스', visible: true,
    basePrice: 30000, combinations: [{ selections: {}, stock: 500 }] }, 201);
  register(admin, keys.s2, { categoryId: data.categoryId, saleMode: 'IN_STOCK', title: 'AOT 학습 메모리', visible: true,
    basePrice: 90000, optionAxes: STORAGE,
    combinations: [{ selections: { storage: '256GB' }, stock: 300 }, { selections: { storage: '512GB' }, stock: 300 }] }, 201);
  register(admin, keys.p1, preorderBody('AOT 학습 폰', opensAt), [200, 202]);
  register(admin, uuid(), { categoryId: data.categoryId, saleMode: 'IN_STOCK', title: 'x', visible: true, basePrice: 1,
    unknownField: true }, 400);

  const p1 = waitCompleted(admin, keys.p1);
  const p2 = waitCompleted(admin, keys.p2);
  const s1 = waitCompleted(admin, keys.s1);
  const s2 = waitCompleted(admin, keys.s2);
  const p1Options = variants(admin, p1);
  const p2Options = variants(admin, p2);
  variants(admin, s1);
  variants(admin, s2);

  // ── 회차가 열릴 때까지 조회로 시간을 쓴다(목록 · 상세 · 배송 차수 · 재고)
  while (Date.now() < opensAt + 2000) {
    call('GET', `${URL.catalog}/api/v1/products?sort=NEWEST`, null, {}, 200, 'catalog list');
    call('GET', `${URL.catalog}/api/v1/products/${p2}`, null, {}, 200, 'catalog detail');
    call('GET', `${URL.preorder}/api/v1/preorders/products/${p1}/shipment-batches`, null, {}, 200, 'preorder shipment batches');
    call('GET', `${URL.order}/api/v1/admin/products/${s2}/stock`, null, { headers: headers(admin) }, 200, 'order stock');
    sleep(1);
  }

  // ── 대기열 일정이 반영됐는지(회차 변경 이벤트 → Redis) 확인하고 입장률을 한 번 설정한다
  call('PUT', `${URL.waitingroom}/api/v1/admin/waitingroom/admission-rate`, { globalCredit: 150 },
    { headers: headers(admin) }, 200, 'waitingroom admission rate');
  for (let i = 0; i < 30; i++) {
    const res = call('GET', `${URL.waitingroom}/api/v1/admin/waitingroom/status`, null, { headers: headers(admin) },
      200, 'waitingroom status');
    const products = (dataOf(res) && dataOf(res).products) || [];
    if (products.some((p) => p.productId === p1)) {
      break;
    }
    sleep(1);
  }

  // ── 접수: 대기열 경유(회원 0~14 → p1), 직접(회원 0~9 → p2), 멱등 재요청 · 중복 모델, 관리자 접수(회원 20~24 → p1)
  for (let i = 0; i < 15; i++) {
    acceptThroughQueue(user(i), p1, p1Options[0]);
  }
  const replayKey = uuid();
  for (let i = 0; i < 10; i++) {
    acceptDirect(user(i), p2, p2Options[i % p2Options.length], i === 0 ? replayKey : uuid(), 202);
  }
  acceptDirect(user(0), p2, p2Options[0], replayKey, 202);
  // 같은 모델 중복은 관리자 접수로 낸다 — 회원 접수는 같은 30초 창의 새 입장권이 직전 접수보다 앞선 것으로 보여 409 가 아니라 403(STALE)이다
  call('POST', `${URL.preorder}/api/v1/admin/preorders`,
    { productId: p2, optionId: p2Options[0], customerId: user(1).customerId, reason: 'AOT 학습 중복 접수' },
    { headers: headers(admin, { 'Idempotency-Key': uuid() }) }, 409, 'preorder admin accept duplicate');
  for (let i = 20; i < 25; i++) {
    call('POST', `${URL.preorder}/api/v1/admin/preorders`,
      { productId: p1, optionId: p1Options[0], customerId: user(i).customerId, reason: 'AOT 학습 관리자 접수' },
      { headers: headers(admin, { 'Idempotency-Key': uuid() }) }, 202, 'preorder admin accept');
  }
}

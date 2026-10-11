// AOT 학습 2부 — REGISTERED 예약으로 주문 · 결제 · 배송을 끝까지 돌리고, 나머지 시간은 서비스마다 대표 조회 · 쓰기 · 오류 경로를 섞는다.
// JIT 프로파일(JEP 515)이 쌓이도록 요청 수가 아니라 시간(data.steadySeconds)으로 돈다.
import exec from 'k6/execution';
import { check, group, sleep } from 'k6';
import { URL, adminLogin, call, data, dataOf, guarded, headers, user, uuid } from './lib.js';

export const options = {
  scenarios: {
    steady: { executor: 'constant-vus', vus: 4, duration: `${data.steadySeconds}s` },
  },
  // 주문 → 결제 → 배송은 예약 수만큼(수십 번)만 돌아 전체 비율에 묻힌다 — 따로 전부 통과해야 한다.
  // 나머지 조회 · 쓰기는 비동기 전파와 겹친 드문 경합을 허용한다.
  thresholds: { 'checks{group:::order}': ['rate==1'], checks: ['rate>0.98'] },
};

const SHIP_TO = { name: '홍길동', phone: '010-1234-5678', postalCode: '06236', line1: '서울 강남구 테헤란로 1', line2: '101호' };
const STEPS = ['PREPARATION_STARTED', 'PACKED', 'SHIPPED', 'DELIVERED'];

export function setup() {
  const admin = adminLogin();
  const res = call('GET', `${URL.catalog}/api/v1/admin/products?q=AOT&size=50`, null, { headers: headers(admin) }, 200,
    'catalog admin list');
  const items = (dataOf(res) && dataOf(res).items) || [];
  return {
    admin,
    preorderProducts: items.filter((p) => p.saleMode === 'PREORDER').map((p) => p.productId),
    inStockProducts: items.filter((p) => p.saleMode === 'IN_STOCK').map((p) => p.productId),
  };
}

// REGISTERED 예약 하나를 주문 → 결제 → (일부) 배송 완료까지. 다섯 번째마다 결제 전 예약 취소(→ order 취소 정산)로 끝낸다.
function orderFlow(ctx, n) {
  const p = data.preorders[n];
  const u = user(p.userIndex);
  const auth = { headers: headers(u.token) };
  const placed = call('POST', `${URL.order}/api/v1/orders`, { source: 'PREORDER', preorderId: p.token, shipTo: SHIP_TO },
    auth, 201, 'order place');
  const order = dataOf(placed);
  if (!order) {
    return;
  }
  call('POST', `${URL.order}/api/v1/orders`, { source: 'PREORDER', preorderId: p.token, shipTo: SHIP_TO }, auth, 200,
    'order place replay');
  if (n % 5 === 4) {
    call('POST', `${URL.preorder}/api/v1/preorders/${p.token}/cancel`, { reason: 'AOT 학습 취소' }, auth, 202,
      'preorder cancel');
    return;
  }
  const attempt = dataOf(call('POST', `${URL.order}/api/v1/orders/${order.orderId}/payment-attempts`, null, auth, 201,
    'order payment attempt'));
  if (!attempt) {
    return;
  }
  const confirmed = call('POST', `${URL.order}/api/v1/orders/${order.orderId}/payment-attempts/${attempt.tossOrderId}/confirm`,
    { paymentKey: `pk_aot_${uuid().replace(/-/g, '')}`, amount: attempt.amount }, auth, 200, 'order payment confirm');
  // 200 이어도 결과가 PENDING 이면 Toss 스텁과 어긋난 것이다 — 그 뒤 배송 단계가 모두 거절되므로 여기서 드러낸다
  check(confirmed, { 'order payment confirm → APPROVED': (r) => dataOf(r) && dataOf(r).result === 'APPROVED' });
  call('POST', `${URL.order}/api/v1/orders/${order.orderId}/payment-attempts/${attempt.tossOrderId}/confirm`,
    { paymentKey: 'pk_aot_wrong_amount', amount: attempt.amount + 1 }, auth, 409, 'order payment confirm mismatch');
  call('GET', `${URL.order}/api/v1/orders/${order.orderId}`, null, auth, 200, 'order detail');
  const steps = n % 3 === 0 ? STEPS : STEPS.slice(0, 1);
  for (const step of steps) {
    call('POST', `${URL.order}/api/v1/admin/orders/${order.orderId}/shipping-steps`, { step, reason: 'AOT 학습' },
      { headers: headers(ctx.admin) }, 200, 'order shipping step');
  }
}

function pick(list) {
  return list[Math.floor(Math.random() * list.length)];
}

// 서비스마다 대표 요청. 회원 화면 · 관리자 화면 · 서비스 간 내부 호출 · 오류 응답을 고루 지난다.
const READS = [
  (ctx, u) => {
    call('GET', `${URL.catalog}/api/v1/products?sort=${pick(['NEWEST', 'PRICE_ASC', 'PRICE_DESC'])}&size=20`, null, {}, 200, 'catalog list');
    call('GET', `${URL.catalog}/api/v1/products?saleMode=IN_STOCK&storage=256GB`, null, {}, 200, 'catalog list filtered');
    call('GET', `${URL.catalog}/api/v1/categories`, null, {}, 200, 'catalog categories');
  },
  (ctx, u) => {
    const id = pick(ctx.preorderProducts.concat(ctx.inStockProducts));
    const detail = dataOf(call('GET', `${URL.catalog}/api/v1/products/${id}`, null, {}, 200, 'catalog detail'));
    if (detail && detail.variants.length) {
      call('GET', `${URL.catalog}/api/v1/products/${id}/variants/${detail.variants[0].variantId}`, null, {}, 200, 'catalog variant');
    }
    call('GET', `${URL.catalog}/api/v1/products/${id}/reviews`, null, {}, 200, 'catalog reviews');
    call('GET', `${URL.catalog}/internal/products/${id}/options`, null, { headers: headers(u.token) }, 200, 'catalog internal options');
    call('GET', `${URL.catalog}/api/v1/products/${uuid()}`, null, {}, 404, 'catalog detail missing');
  },
  (ctx, u) => {
    call('GET', `${URL.catalog}/api/v1/admin/products?size=20`, null, { headers: headers(ctx.admin) }, 200, 'catalog admin list');
    const id = pick(ctx.inStockProducts);
    call('GET', `${URL.catalog}/api/v1/admin/products/${id}`, null, { headers: headers(ctx.admin) }, 200, 'catalog admin detail');
    call('PATCH', `${URL.catalog}/api/v1/admin/products/${id}/visibility`, { visible: true }, { headers: headers(ctx.admin) }, 200,
      'catalog visibility');
    call('PATCH', `${URL.catalog}/api/v1/admin/products/${id}/sale-status`, { status: 'ACTIVE' }, { headers: headers(ctx.admin) },
      200, 'catalog sale status');
  },
  (ctx, u) => {
    const auth = { headers: headers(u.token) };
    call('GET', `${URL.member}/api/v1/session`, null, auth, 200, 'member session');
    call('GET', `${URL.member}/api/v1/me/profile`, null, auth, 200, 'member profile');
    call('PUT', `${URL.member}/api/v1/me/profile`, { name: '홍길동', email: 'aot@example.com', phoneNumber: '010-1234-5678' },
      auth, 200, 'member profile update');
    call('PUT', `${URL.member}/api/v1/me/default-address`, SHIP_TO, auth, 200, 'member address update');
    call('GET', `${URL.member}/api/v1/me/default-address`, null, auth, 200, 'member address');
    call('GET', `${URL.member}/.well-known/jwks.json`, null, {}, 200, 'member jwks');
    call('GET', `${URL.member}/api/v1/me/profile`, null, {}, 401, 'member profile unauthenticated');
    call('DELETE', `${URL.member}/api/v1/session`, null, {}, 204, 'member logout without session');
  },
  (ctx, u) => {
    const auth = { headers: headers(u.token) };
    const list = dataOf(call('GET', `${URL.preorder}/api/v1/preorders?size=20`, null, auth, 200, 'preorder list'));
    if (list && list.items.length) {
      const token = list.items[0].preorderId;
      call('GET', `${URL.preorder}/api/v1/preorders/${token}`, null, auth, 200, 'preorder detail');
      call('GET', `${URL.preorder}/api/v1/preorders/${token}/history`, null, auth, 200, 'preorder history');
    }
    call('GET', `${URL.preorder}/api/v1/preorders/products/${pick(ctx.preorderProducts)}/shipment-batches`, null, {}, 200,
      'preorder shipment batches');
    call('GET', `${URL.preorder}/api/v1/preorders/${uuid()}`, null, auth, 404, 'preorder detail missing');
  },
  (ctx, u) => {
    const admin = { headers: headers(ctx.admin) };
    call('GET', `${URL.preorder}/api/v1/admin/preorders?size=20`, null, admin, 200, 'preorder admin list');
    call('GET', `${URL.preorder}/api/v1/admin/preorders/sync-jobs?jobType=REGISTER&size=20`, null, admin, 200, 'preorder sync jobs');
    call('GET', `${URL.preorder}/api/v1/admin/preorders/dead-letters?size=20`, null, admin, 200, 'preorder dead letters');
    call('GET', `${URL.preorder}/api/v1/admin/preorders`, null, { headers: headers(u.token) }, 403, 'preorder admin forbidden');
  },
  (ctx, u) => {
    const auth = { headers: headers(u.token) };
    call('GET', `${URL.order}/api/v1/orders?size=20`, null, auth, 200, 'order list');
    call('GET', `${URL.order}/api/v1/admin/orders?size=20`, null, { headers: headers(ctx.admin) }, 200, 'order admin list');
    call('GET', `${URL.order}/api/v1/orders`, null, {}, 401, 'order list unauthenticated');
    call('POST', `${URL.order}/api/v1/orders`, { source: 'PREORDER', shipTo: SHIP_TO }, auth, 400, 'order place invalid');
  },
  (ctx, u) => {
    const admin = { headers: headers(ctx.admin) };
    const id = pick(ctx.inStockProducts);
    const stock = dataOf(call('GET', `${URL.order}/api/v1/admin/products/${id}/stock`, null, admin, 200, 'order stock'));
    if (stock && stock.items.length) {
      call('PUT', `${URL.order}/api/v1/admin/products/${id}/stock`,
        { items: stock.items.map((it) => ({ optionId: it.optionId, stockTotal: 300 + Math.floor(Math.random() * 100) })) },
        admin, 200, 'order stock set');
    }
    call('GET', `${URL.order}/api/v1/admin/products/${pick(ctx.preorderProducts)}/stock`, null, admin, 200,
      'order stock not tracked');
  },
  (ctx, u) => {
    const admin = { headers: headers(ctx.admin) };
    call('GET', `${URL.waitingroom}/api/v1/admin/waitingroom/status`, null, admin, 200, 'waitingroom status');
    call('GET', `${URL.waitingroom}/api/v1/admin/waitingroom/admission-rate`, null, admin, 200, 'waitingroom admission rate');
    call('POST', `${URL.waitingroom}/api/v1/preorders/queue?productId=${pick(ctx.preorderProducts)}`, null,
      { headers: headers(u.token) }, [200, 202], 'waitingroom enter');
  },
  (ctx, u) => {
    // 쓰기: 일반 상품 등록(→ order 재고 초기화 이벤트)
    call('POST', `${URL.catalog}/api/v1/admin/products`,
      { categoryId: data.categoryId, saleMode: 'IN_STOCK', title: `AOT 학습 액세서리 ${uuid().slice(0, 8)}`, visible: true,
        basePrice: 15000, combinations: [{ selections: {}, stock: 50 }] },
      { headers: headers(ctx.admin, { 'Idempotency-Key': uuid() }) }, 201, 'catalog register');
  },
];

export default function (ctx) {
  const n = exec.scenario.iterationInTest;
  if (n < data.preorders.length) {
    group('order', () => guarded('order flow', () => orderFlow(ctx, n)));
  } else {
    guarded('reads', () => pick(READS)(ctx, user(n)));
  }
  sleep(0.05);
}

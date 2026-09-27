// 게이트웨이와 같은 입장권(et_): base64url(productId · customerId · exp) 에 HMAC-SHA256 서명. exp 는 30초 창 시작 + 120초.
import crypto from 'k6/crypto';
import encoding from 'k6/encoding';

const FIELD = '\u001f';
const TTL_SECONDS = 120;
const WINDOW_SECONDS = 30;

export function issueAdmissionTicket(secret, productId, customerId, nowEpochSeconds) {
  const exp = Math.floor(nowEpochSeconds / WINDOW_SECONDS) * WINDOW_SECONDS + TTL_SECONDS;
  const payload = encoding.b64encode(`${productId}${FIELD}${customerId}${FIELD}${exp}`, 'rawurl');
  const signature = crypto.hmac('sha256', secret, `et_${payload}`, 'base64rawurl');
  return `et_${payload}.${signature}`;
}

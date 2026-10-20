package com.grandis.nova.batch.reconciliation.run;

import java.time.Instant;

/** 앞 실행의 불일치 하나에서 처음 발견 시각을 잇는 데 쓰는 값 — (코드, 대상)이 같으면 이 시각을 물려받는다 */
record FirstDetected(String checkCode, String targetKey, Instant firstDetectedAt) {
}

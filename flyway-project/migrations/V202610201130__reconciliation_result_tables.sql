-- 정합성 검사 결과 ------------------------------------------
-- batch 가 be 예약 · Mock 원장 · be 내부 데이터를 대조한 결과를 남기고, preorder 가 관리자 화면에 보여 주며 실행 요청 · 조치 상태를 쓴다.
--
-- 왜: 화면에 실행 중 표시 · 실행 이력 · 실행 사이 비교(신규 · 잔여 · 해소) · 건별 조치 상태가 있다. 실행이 끝난 뒤에도 바뀌는
--     값이라 한 번 쓰고 끝나는 보고서 파일로는 담기 어렵다.
-- 표: reconciliation_runs(검사 실행 한 번) · reconciliation_findings(찾은 불일치 한 건)
-- 상태
--   관리자 실행   preorder 가 REQUESTED 로 넣음 → batch 가 가져가 RUNNING → COMPLETED · FAILED
--   정기 실행     batch 가 RUNNING 으로 바로 넣음 → COMPLETED · FAILED
--   batch 재시작  남은 REQUESTED · RUNNING → FAILED("batch 재시작으로 중단")
-- 쓰는 쪽: batch 전용 계정은 업무 테이블을 읽기만 하고 이 두 표에만 쓴다(runs INSERT · UPDATE, findings INSERT).
--     preorder 는 실행 요청을 넣고 건별 조치 컬럼만 바꾼다.
-- 업무 테이블로 가는 외래 키는 두지 않는다 — 결과는 검사 때 본 값의 기록이고, 넣을 때 업무 행을 잠그지 않게 한다.
-- 배포: 새 표만 더하므로 서비스 배포 전에 적용해도 된다.
CREATE TABLE shop.reconciliation_runs (
    -- id · created_at · updated_at 은 be 공통 엔티티(BaseEntity)와 같다. id 는 UUID v7 이지만 같은 밀리초 안의 순서가 보장되지 않아
    -- 최근 실행 목록은 id 가 아니라 created_at 순으로 읽는다
    id                 binary(16)   NOT NULL,
    -- 관리자 실행을 요청한 관리자(인증 주체 이름). 비어 있으면 정기 실행이다(DLQ 테이블의 redrive_requested_by 와 같은 방식)
    requested_by       varchar(64)  NULL,
    product_id         binary(16)   NULL,       -- 검사 범위. 비어 있으면 전체, 있으면 그 사전예약 상품 하나(화면의 모델 이름은 catalog 에서 읽는다)
    status             varchar(10)  NOT NULL,   -- REQUESTED · RUNNING · COMPLETED · FAILED
    -- 대기 · 실행 중이면 1, 끝나면 NULL. UNIQUE 로 대기 · 실행 중인 실행을 하나로 막는다(NULL 끼리는 중복 허용)
    active_marker      tinyint      GENERATED ALWAYS AS
                                      (CASE WHEN status IN ('REQUESTED','RUNNING') THEN 1 ELSE NULL END) STORED,
    -- 검사를 시작한 시각(be 스냅샷을 연 시각)과 판정 기준 시각(= 시작 − 여유). 기준 시각 뒤에 바뀐 키는 판정하지 않고 held_count 로만 센다.
    -- 여유 값이 바뀌어도 그 실행이 어떤 기준으로 판정했는지 남도록 기준 시각을 둔다
    started_at         datetime(6)  NULL,
    cutoff_at          datetime(6)  NULL,
    finished_at        datetime(6)  NULL,
    error_message      varchar(500) NULL,       -- FAILED 사유. 예: "Mock 목록 조회 실패(3페이지, 시간 초과)"
    -- 건별 행 없이 세기만 하는 것(완료 때 채운다). 화면의 판정 보류 · 미완료 현황
    held_count         int          NULL,       -- 판정 보류 — 기준 시각 뒤에 바뀐 키
    pending_sync_count int          NULL,       -- 처리 중 — 등록 대기(등록 작업 PENDING · PROCESSING · RETRY_SCHEDULED)
    canceling_count    int          NULL,       -- 처리 중 — 취소 중
    dead_letter_count  int          NULL,       -- 관리자 대기 — 등록 · 취소 작업이 DEAD_LETTER
    created_at         datetime(6)  NOT NULL,   -- 관리자 실행은 요청한 시각, 정기 실행은 시작한 시각
    updated_at         datetime(6)  NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uq_reconciliation_run_active (active_marker),
    KEY ix_reconciliation_run_created (created_at, id),
    CONSTRAINT ck_reconciliation_run_status    CHECK (status IN ('REQUESTED','RUNNING','COMPLETED','FAILED')),
    -- 정기 실행은 batch 가 RUNNING 으로 바로 넣는다. REQUESTED 는 관리자 요청뿐이다
    CONSTRAINT ck_reconciliation_run_requested CHECK (status <> 'REQUESTED' OR requested_by IS NOT NULL),
    -- 시작 시각과 기준 시각은 함께 채운다. 실행 중 · 완료는 둘 다 있고, 대기는 둘 다 없다.
    -- 실패는 둘 다일 수 있다 — batch 가 다시 뜰 때 시작하지 못한 REQUESTED 도 실패로 정리한다
    CONSTRAINT ck_reconciliation_run_started   CHECK (
        (started_at IS NULL) = (cutoff_at IS NULL)
        AND (cutoff_at IS NULL OR cutoff_at <= started_at)
        AND (status <> 'REQUESTED' OR started_at IS NULL)
        AND (status NOT IN ('RUNNING','COMPLETED') OR started_at IS NOT NULL)),
    CONSTRAINT ck_reconciliation_run_finished  CHECK ((status IN ('COMPLETED','FAILED')) = (finished_at IS NOT NULL)),
    CONSTRAINT ck_reconciliation_run_error     CHECK ((status = 'FAILED') = (error_message IS NOT NULL)),
    CONSTRAINT ck_reconciliation_run_completed CHECK (status <> 'COMPLETED' OR (
        held_count IS NOT NULL AND pending_sync_count IS NOT NULL
        AND canceling_count IS NOT NULL AND dead_letter_count IS NOT NULL))
) ENGINE = InnoDB;

CREATE TABLE shop.reconciliation_findings (
    id                  binary(16)   NOT NULL,
    run_id              binary(16)   NOT NULL,
    -- 확인할 것 코드(3장). 화면의 유형 · 문구 · 조치 버튼은 코드에서 정한다.
    -- 종류가 늘 때마다 마이그레이션이 붙지 않게 CHECK 를 두지 않는다(아웃박스 event_type 과 같다)
    check_code          varchar(40)  NOT NULL,
    -- 불일치 대상 — 예약 · Mock 건은 외부 키(be preorder_token = Mock externalKey), 순번 빈틈은 상품 id, 재고는 옵션 id(UUID 문자열).
    -- 상세보기 · 조치도 이 값으로 찾는다(관리자 예약 상세는 외부 키로도 열린다).
    -- 실행 사이 비교(신규 · 잔여 · 해소)와 처음 발견 시각을 (check_code, target_key) 로 찾는다
    target_key          varchar(100) COLLATE utf8mb4_bin NOT NULL,
    -- 검사 때 본 값. 화면의 우리 · 외부 열과 상세보기를 여기서 그린다. 실행 뒤에 데이터가 바뀌어도 그때 본 값이 남는다
    be_snapshot         json         NULL,       -- 예약 상태 · 외부 번호 · 회원 · 상품 · sku · 작업 상태 · 시각
    mock_snapshot       json         NULL,       -- Mock 목록의 그 키 행
    -- 이 (check_code, target_key) 를 처음 발견한 시각. 앞 실행에도 있었으면 그 값을 이어받고, 없으면 이 실행의 시작 시각.
    -- 여러 실행에 걸쳐 남은 불일치를 일시적인 것과 가른다
    first_detected_at   datetime(6)  NOT NULL,
    -- 조치. batch 는 NOT_STARTED 로만 넣고, preorder 가 관리자 버튼을 받아 바꾼다
    action_status       varchar(20)  NOT NULL,   -- NOT_STARTED · REQUESTED · SUCCEEDED · ALREADY_RESOLVED · FAILED
    -- 무슨 조치를 했는지. 코드별 조치 버튼은 바뀔 수 있어(외부 번호 맞추기 추가 등) 그때 한 조치를 값으로 남긴다
    action_type         varchar(20)  NULL,       -- EXTERNAL_CANCEL · REPROCESS · ADMIN_CANCEL · STOCK_ADJUST
    action_requested_by varchar(64)  NULL,       -- 조치를 누른 관리자(인증 주체 이름)
    action_requested_at datetime(6)  NULL,       -- 조치를 누른 시각
    action_note         varchar(500) NULL,       -- 조치 실패 사유(FAILED 면 필수) · 수동 조사 메모
    created_at          datetime(6)  NOT NULL,
    updated_at          datetime(6)  NOT NULL,
    PRIMARY KEY (id),
    -- 한 실행에서 같은 건은 한 번만. 실행 · 코드로 거르는 목록 조회도 이 인덱스로 읽는다
    UNIQUE KEY uq_reconciliation_finding_target (run_id, check_code, target_key),
    -- 앞 실행에서 같은 건 찾기(처음 발견 시각 · 실행 사이 비교)
    KEY ix_reconciliation_finding_history (check_code, target_key, run_id),
    CONSTRAINT fk_reconciliation_finding_run FOREIGN KEY (run_id) REFERENCES shop.reconciliation_runs (id),
    CONSTRAINT ck_reconciliation_finding_snapshot CHECK (be_snapshot IS NOT NULL OR mock_snapshot IS NOT NULL),
    CONSTRAINT ck_reconciliation_finding_action_status CHECK (
        action_status IN ('NOT_STARTED','REQUESTED','SUCCEEDED','ALREADY_RESOLVED','FAILED')),
    CONSTRAINT ck_reconciliation_finding_action_type CHECK (
        action_type IS NULL OR action_type IN ('EXTERNAL_CANCEL','REPROCESS','ADMIN_CANCEL','STOCK_ADJUST')),
    -- 조치 전에는 조치 컬럼이 모두 비고, 조치를 시작하면 무엇을 · 누가 · 언제가 모두 있다
    CONSTRAINT ck_reconciliation_finding_action_requested CHECK (
        (action_status = 'NOT_STARTED' AND action_type IS NULL AND action_requested_by IS NULL AND action_requested_at IS NULL)
        OR (action_status <> 'NOT_STARTED' AND action_type IS NOT NULL AND action_requested_by IS NOT NULL
            AND action_requested_at IS NOT NULL)),
    CONSTRAINT ck_reconciliation_finding_action_failed CHECK (action_status <> 'FAILED' OR action_note IS NOT NULL)
) ENGINE = InnoDB;

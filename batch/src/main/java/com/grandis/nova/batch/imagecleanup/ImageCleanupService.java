package com.grandis.nova.batch.imagecleanup;

import com.grandis.nova.batch.imagecleanup.ImageCleanupStore.Candidate;
import com.grandis.nova.batch.imagecleanup.ImageCleanupStore.Position;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import software.amazon.awssdk.core.exception.SdkException;

/**
 * 안 쓰는 상품 · 홍보 사진 정리. catalog 가 올린 사진은 images 에 행이 있고, 상품 · 홍보에 붙지 않으면 UNUSED 다. 기준 시간이 지난
 * UNUSED 사진을 표와 S3 에서 지운다. 결과는 실행마다 로그 한 줄로 남긴다.
 *
 * 한 장씩: 행을 id 로 지우고 같은 트랜잭션에 삭제 대기를 넣어 커밋한 뒤 S3 객체를 지운다. S3 를 커밋 전에 지우면, 지운 뒤 커밋 전에
 * 멈출 때 행이 되살아나(UNUSED) 파일 없는 사진을 붙일 수 있다. S3 삭제가 성공하면 삭제 대기를 지우고, 남은 삭제 대기(S3 실패 · 그 사이
 * 멈춤)는 다음 실행이 처음에 마저 지운다.
 *
 * 대상은 (updated_at, id) 로 이어 읽는다 — 드로우 · 주문이 가리켜 못 지운 행은 남는데, 매번 처음부터 고르면(LIMIT 만) 그런 행이 청크를
 * 채웠을 때 뒤의 사진에 닿지 못한다.
 *
 * S3 삭제가 재시도 뒤에도 실패하면 이번 실행을 멈춘다. S3 가 멈췄으면 남은 대상도 실패할 것이고, 한 건마다 시간 상한까지 기다리면
 * 스케줄러 스레드를 함께 쓰는 정합성 검사가 그만큼 밀린다. 남은 대상은 다음 실행이 본다. 실패가 이어지면(삭제 대기가
 * {@link #STALE_PENDING} 넘게 남음) 실행마다 오류 로그를 따로 남긴다 — 한 번 실패와 며칠째 막힌 정리를 로그로 가른다.
 *
 * dry-run 은 같은 조건으로 대상을 세고 오래된 순 몇 건의 키를 로그로 남길 뿐, 행 · 삭제 대기 · S3 를 건드리지 않는다.
 */
@Service
public class ImageCleanupService {

    /** dry-run 로그에 남기는 오래된 순 키 수 */
    static final int PREVIEW_KEYS = 5;
    /** 삭제 대기가 이만큼 남아 있으면 오류 로그를 남긴다. 하루 한 번 돌므로 세 번 연속 S3 삭제가 실패한 것이다 */
    static final Duration STALE_PENDING = Duration.ofDays(3);

    private static final Logger log = LoggerFactory.getLogger(ImageCleanupService.class);

    private final ImageCleanupStore store;
    private final ImageObjects objects;
    private final ImageCleanupLock lock;
    private final ImageCleanupProperties properties;
    private final Clock clock;
    private final TransactionTemplate transaction;

    ImageCleanupService(ImageCleanupStore store, ImageObjects objects, ImageCleanupLock lock,
                        ImageCleanupProperties properties, Clock clock, PlatformTransactionManager transactionManager) {
        this.store = store;
        this.objects = objects;
        this.lock = lock;
        this.properties = properties;
        this.clock = clock;
        this.transaction = new TransactionTemplate(transactionManager);
        // 행 삭제는 드로우 회차 · 주문 줄을 끝까지 훑는 하위 조회를 품고, MySQL 은 DELETE 안의 하위 조회가 훑는 행을 공유 잠금으로 읽는다.
        // REPEATABLE READ(MySQL 기본값)면 훑은 행과 그 사이(갭)를 커밋까지 쥐어 그동안 새 주문 넣기 · 주문 줄 고치기가 기다린다.
        // READ COMMITTED 면 조건에 안 맞는 행의 잠금을 바로 놓고 갭을 잠그지 않아 주문을 막지 않는다. 다만 훑다가 주문이 쓰는 중인(커밋 전)
        // 행을 만나면 그 주문이 커밋할 때까지 정리가 기다린다 — 기다림은 주문 트랜잭션 길이만큼이다.
        // 연결 설정(hikari.transaction-isolation)을 빠뜨려도 READ COMMITTED 로 돌도록 코드로 정한다 — 정합성 검사가 REPEATABLE READ 를
        // 직접 거는 것과 같은 방식이다
        this.transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    /** 정기 실행. 다른 실행이 잠금을 쥐고 있으면 건너뛴다 */
    public void run() {
        if (!lock.runExclusively(this::cleanUp)) {
            log.info("사진 정리 건너뜀 — 다른 실행이 잠금을 쥐고 있다");
        }
    }

    private void cleanUp() {
        long started = System.nanoTime();
        Instant cutoff = clock.instant().minus(properties.retention());
        if (properties.dryRun()) {
            preview(cutoff, started);
            return;
        }
        Tally tally = new Tally();
        boolean stopped = !deletePending(tally) || !deleteUnused(cutoff, tally);
        log.info("사진 정리 — 지난 실행의 삭제 대기 {}건 지움 · 기준 시각 {} 전에 UNUSED 가 된 사진 {}건: 지움 {} · 드로우 · 지난 주문이 가리켜 남김 {} · "
                        + "그 사이 바뀌어 건너뜀 {} · 걸린 시간 {}{}",
                tally.pendingDeleted, cutoff, tally.candidates, tally.deleted, tally.referenced, tally.changed, elapsed(started),
                stopped ? " — S3 삭제 실패로 멈춤(남은 대상은 다음 실행에)" : "");
        warnStalePending();
    }

    /** 오래 남은 삭제 대기가 있으면 오류 로그 — S3 삭제가 며칠째 실패해 정리가 막혀 있다 */
    private void warnStalePending() {
        ImageCleanupStore.StalePending stale = store.stalePending(clock.instant().minus(STALE_PENDING));
        if (stale.count() > 0) {
            log.error("사진 정리가 막혀 있다 — 삭제 대기 {}건이 {} 부터 남아 있다({} 넘게 S3 삭제 실패). 업로드 버킷 · 리전 · 삭제 권한을 확인한다",
                    stale.count(), stale.oldest(), STALE_PENDING);
        }
    }

    /** 지난 실행이 남긴 삭제 대기의 S3 객체를 지운다. S3 가 실패하면 false */
    private boolean deletePending(Tally tally) {
        String after = "";
        while (true) {
            List<String> keys = store.pending(after, properties.chunkSize());
            for (String key : keys) {
                if (!deleteObject(key)) {
                    return false;
                }
                tally.pendingDeleted++;
            }
            if (keys.size() < properties.chunkSize()) {
                return true;
            }
            after = keys.getLast();
        }
    }

    /** 기준 시간이 지난 UNUSED 사진을 지운다. S3 가 실패하면 false */
    private boolean deleteUnused(Instant cutoff, Tally tally) {
        Position after = null;
        while (true) {
            List<Candidate> chunk = store.candidates(cutoff, after, properties.chunkSize());
            for (Candidate image : chunk) {
                tally.candidates++;
                if (image.referenced()) {
                    tally.referenced++;
                } else if (!deleteRow(image, cutoff)) {
                    tally.changed++;
                } else if (deleteObject(image.objectKey())) {
                    tally.deleted++;
                } else {
                    return false;
                }
            }
            if (chunk.size() < properties.chunkSize()) {
                return true;
            }
            after = chunk.getLast().position();
        }
    }

    /** 행을 지우고 같은 트랜잭션에 삭제 대기를 넣는다. 고른 뒤 바뀌어 조건에 안 맞으면(0행) false */
    private boolean deleteRow(Candidate image, Instant cutoff) {
        return Boolean.TRUE.equals(transaction.execute(status -> {
            if (!store.deleteIfStillUnused(image.id(), cutoff)) {
                return false;
            }
            store.addPending(image.objectKey(), clock.instant());
            return true;
        }));
    }

    /** S3 객체를 지우고 삭제 대기를 지운다. 실패하면 삭제 대기를 남기고 false */
    private boolean deleteObject(String key) {
        try {
            objects.delete(key);
        } catch (SdkException e) {
            log.warn("사진 S3 삭제 실패 — 삭제 대기로 남겨 다음 실행이 다시 지운다: key={}", key, e);
            return false;
        }
        store.removePending(key);
        return true;
    }

    private void preview(Instant cutoff, long started) {
        int candidates = 0;
        int referenced = 0;
        List<String> oldest = new ArrayList<>();
        Position after = null;
        while (true) {
            List<Candidate> chunk = store.candidates(cutoff, after, properties.chunkSize());
            for (Candidate image : chunk) {
                candidates++;
                if (image.referenced()) {
                    referenced++;
                } else if (oldest.size() < PREVIEW_KEYS) {
                    oldest.add(image.objectKey() + "(" + image.updatedAt() + ")");
                }
            }
            if (chunk.size() < properties.chunkSize()) {
                break;
            }
            after = chunk.getLast().position();
        }
        log.info("사진 정리(dry-run, 지우지 않음) — 기준 시각 {} 전에 UNUSED 가 된 사진 {}건: 지울 대상 {} · 드로우 · 지난 주문이 가리켜 남김 {} · "
                        + "지난 실행의 삭제 대기 {}건 · 걸린 시간 {}. 오래된 순 {}",
                cutoff, candidates, candidates - referenced, referenced, store.pendingCount(), elapsed(started), oldest);
    }

    /** 실행에 걸린 시간 — 드로우 · 주문 줄을 훑는 확인 비용이 데이터와 함께 늘어나는지 로그로 본다 */
    private static String elapsed(long startedNanos) {
        return "%.1f초".formatted((System.nanoTime() - startedNanos) / 1_000_000_000.0);
    }

    /** 한 실행에서 센 건수 */
    private static final class Tally {

        int pendingDeleted;
        int candidates;
        int deleted;
        int referenced;
        int changed;
    }
}

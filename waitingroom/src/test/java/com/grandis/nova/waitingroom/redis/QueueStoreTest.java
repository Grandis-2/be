package com.grandis.nova.waitingroom.redis;

import com.grandis.nova.waitingroom.domain.queue.AdmissionTicket;
import com.grandis.nova.waitingroom.domain.queue.GraceRetention;
import com.grandis.nova.waitingroom.domain.queue.QueueEntry;
import com.grandis.nova.waitingroom.domain.queue.QueueState;
import com.grandis.nova.waitingroom.redis.QueueStore.QueueStatus;
import com.grandis.nova.waitingroom.support.RedisContainer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static com.grandis.nova.waitingroom.support.TestIds.productKey;
import static org.assertj.core.api.Assertions.assertThat;

class QueueStoreTest {

    static final String PRODUCT = productKey(101);
    static final Duration WAIT = Duration.ofSeconds(5);

    private ReactiveStringRedisTemplate redis;
    private QueueStore queue;
    private ControlStore control;
    private Instant now;
    private long round;

    @BeforeEach
    void setUp() {
        redis = RedisContainer.fresh();
        LuaScripts scripts = new LuaScripts(redis);
        queue = new QueueStore(scripts);
        control = new ControlStore(scripts, redis);
        now = Instant.now();
    }

    private QueueEntry enqueue(String customer) {
        return queue.enqueue(PRODUCT, customer, QueueStore.UNLIMITED, now).block(WAIT).entry();
    }

    private QueueStatus status(String customer) {
        return queue.status(PRODUCT, customer, now).block(WAIT);
    }

    private void admit(long count) {
        control.apply(PRODUCT, count, 1, 60_000, -1, ++round).block(WAIT);
    }

    @Nested
    class 줄_서기 {

        @Test
        void 도착_순서대로_서고_다시_서면_같은_자리를_돌려준다() {
            QueueEntry first = enqueue("a");
            QueueEntry second = enqueue("b");
            QueueEntry again = enqueue("a");

            assertThat(first.rank()).isZero();
            assertThat(second.rank()).isEqualTo(1);
            assertThat(second.score()).isGreaterThan(first.score());
            assertThat(again.alreadyQueued()).isTrue();
            assertThat(again.score()).isEqualTo(first.score());
        }

        @Test
        void 같은_고객이_동시에_여러_번_서도_한_자리만_잡는다() {
            List<QueueEntry> entries = Flux.range(0, 32)
                    .flatMap(i -> queue.enqueue(PRODUCT, "a", QueueStore.UNLIMITED, now).map(QueueStatus::entry), 32)
                    .collectList().block(WAIT);

            assertThat(redis.opsForZSet().size("wr:queue:{" + PRODUCT + "}").block(WAIT)).isEqualTo(1);
            assertThat(entries).extracting(QueueEntry::score).containsOnly(entries.get(0).score());
            assertThat(entries).filteredOn(entry -> !entry.alreadyQueued()).hasSize(1);
        }

        @Test
        void 시계가_뒤로_가도_바닥값_위에_서_앞사람을_추월하지_않는다() {
            QueueEntry first = enqueue("a");
            long future = first.score() + 10_000_000;
            redis.opsForValue().set("wr:maxscore:{" + PRODUCT + "}", String.valueOf(future)).block(WAIT);

            QueueEntry second = enqueue("b");

            assertThat(second.score()).isEqualTo(future + 1);
            assertThat(second.clockWentBack()).isTrue();
        }

        @Test
        void 상한을_정하면_기다리는_사람만_세어_넘치면_거절하고_이미_선_사람은_그대로다() {
            assertThat(queue.enqueue(PRODUCT, "a", 1, now).block(WAIT).entry().accepted()).isTrue();
            assertThat(queue.enqueue(PRODUCT, "b", 1, now).block(WAIT).entry().accepted()).isFalse();
            assertThat(queue.enqueue(PRODUCT, "a", 1, now).block(WAIT).entry().alreadyQueued()).isTrue();

            admit(1);
            assertThat(queue.enqueue(PRODUCT, "b", 1, now).block(WAIT).entry().accepted()).as("입장한 사람은 세지 않는다").isTrue();
        }
    }

    @Nested
    class 조회 {

        @Test
        void 줄에_없으면_맨_앞과_구분해_NOT_QUEUED_다() {
            assertThat(status("ghost").entry().state()).isEqualTo(QueueState.NOT_QUEUED);
        }

        @Test
        void 앞_인원과_총원을_같은_기준으로_센다() {
            enqueue("a");
            enqueue("b");
            enqueue("c");

            QueueEntry b = status("b").entry();
            assertThat(b.rank()).isEqualTo(1);
            assertThat(b.total()).isEqualTo(3);
            assertThat(b.behind()).isEqualTo(1);
        }

        @Test
        void 커서가_넘으면_입장이고_다시_조회해도_같은_입장_시각을_돌려준다() {
            enqueue("a");
            enqueue("b");
            admit(1);

            QueueStatus admitted = status("a");
            Instant later = now.plusSeconds(45);
            QueueStatus again = queue.status(PRODUCT, "a", later).block(WAIT);

            assertThat(admitted.entry().state()).isEqualTo(QueueState.ADMITTED);
            assertThat(admitted.admittedAt()).isEqualTo(Instant.ofEpochSecond(now.getEpochSecond()));
            assertThat(again.admittedAt()).as("입장권을 매번 같게 만드는 기준").isEqualTo(admitted.admittedAt());
            assertThat(status("b").entry().rank()).as("입장한 사람은 앞 인원에서 빠진다").isZero();
        }

        @Test
        void 입장한_사람이_다시_진입해도_새로_서지_않고_같은_입장_시각을_받는다() {
            enqueue("a");
            enqueue("b");
            admit(1);
            QueueStatus admitted = status("a");

            QueueStatus again = queue.enqueue(PRODUCT, "a", QueueStore.UNLIMITED, now.plusSeconds(30)).block(WAIT);

            assertThat(again.entry().state()).isEqualTo(QueueState.ADMITTED);
            assertThat(again.admittedAt()).isEqualTo(admitted.admittedAt());
            assertThat(status("b").entry().rank()).as("뒤에 다시 서지 않았다").isZero();
        }

        @Test
        void 커서_아래인데_아직_조회하지_않은_사람이_다시_진입하면_그때를_입장_시각으로_알린다() {
            enqueue("a");
            admit(1);

            QueueStatus again = queue.enqueue(PRODUCT, "a", QueueStore.UNLIMITED, now).block(WAIT);

            assertThat(again.entry().state()).isEqualTo(QueueState.ADMITTED);
            assertThat(status("a").admittedAt()).isEqualTo(again.admittedAt());
        }

        @Test
        void 입장권이_만료되기_전까지는_같은_입장을_돌려준다() {
            enqueue("a");
            admit(1);
            Instant admittedAt = status("a").admittedAt();
            Instant expiresAt = Instant.ofEpochSecond(admittedAt.getEpochSecond() / AdmissionTicket.WINDOW_SEC
                    * AdmissionTicket.WINDOW_SEC + AdmissionTicket.TTL_SEC);

            assertThat(queue.status(PRODUCT, "a", expiresAt.minusSeconds(1)).block(WAIT).admittedAt()).isEqualTo(admittedAt);
            assertThat(queue.status(PRODUCT, "a", expiresAt).block(WAIT).entry().state()).as("입장권과 같은 식으로 만료")
                    .isEqualTo(QueueState.NOT_QUEUED);
        }

        @Test
        void 청소가_입장_미통지로_옮긴_사람은_다시_조회하거나_진입하면_그때_입장을_알린다() {
            enqueue("a");
            enqueue("b");
            enqueue("c");
            admit(2);
            long later = now.getEpochSecond() + 1_000;
            queue.status(PRODUCT, "c", Instant.ofEpochSecond(later)).block(WAIT);
            control.sweep(PRODUCT, 100, later, 3_000, 100, "0", true, 1).block(WAIT);

            QueueStatus byStatus = queue.status(PRODUCT, "a", Instant.ofEpochSecond(later)).block(WAIT);
            QueueStatus byEnqueue = queue.enqueue(PRODUCT, "b", QueueStore.UNLIMITED, Instant.ofEpochSecond(later)).block(WAIT);

            assertThat(byStatus.entry().state()).isEqualTo(QueueState.ADMITTED);
            assertThat(byStatus.admittedAt()).isEqualTo(Instant.ofEpochSecond(later));
            assertThat(byEnqueue.entry().state()).isEqualTo(QueueState.ADMITTED);
            assertThat(queue.status(PRODUCT, "c", Instant.ofEpochSecond(later)).block(WAIT).entry().rank()).isZero();
        }

        @Test
        void 보관_기간이_지난_입장_미통지는_조회도_진입도_입장으로_보지_않고_진입하면_맨_뒤에_선다() {
            enqueue("a");
            enqueue("b");
            admit(1);
            long later = now.getEpochSecond() + 1_000;
            queue.status(PRODUCT, "b", Instant.ofEpochSecond(later)).block(WAIT);
            control.sweep(PRODUCT, 100, later, 3_000, 100, "0", true, 1).block(WAIT);
            Instant expired = Instant.ofEpochSecond(later + GraceRetention.SECONDS + 1);

            assertThat(queue.status(PRODUCT, "a", expired).block(WAIT).entry().state()).isEqualTo(QueueState.NOT_QUEUED);
            QueueEntry back = queue.enqueue(PRODUCT, "a", QueueStore.UNLIMITED, expired).block(WAIT).entry();
            assertThat(back.state()).isEqualTo(QueueState.WAITING);
            assertThat(back.rank()).isEqualTo(1);
            assertThat(redis.opsForHash().get("wr:grace:{" + PRODUCT + "}", "a").block(WAIT)).as("낡은 기록은 지웠다").isNull();
        }

        @Test
        void 입장권_수명이_지난_입장은_끝난_것이라_조회는_줄에_없고_다시_진입하면_맨_뒤에_선다() {
            enqueue("a");
            enqueue("b");
            admit(1);
            status("a");
            Instant expired = now.plusSeconds(AdmissionTicket.TTL_SEC);

            assertThat(queue.status(PRODUCT, "a", expired).block(WAIT).entry().state()).isEqualTo(QueueState.NOT_QUEUED);
            QueueEntry back = queue.enqueue(PRODUCT, "a", QueueStore.UNLIMITED, expired).block(WAIT).entry();
            assertThat(back.state()).isEqualTo(QueueState.WAITING);
            assertThat(back.rank()).isEqualTo(1);
        }
    }

    @Test
    void 무작위로_서고_입장하고_조회해도_대기자의_앞_인원은_늘지_않고_입장은_도착_순서대로다() {
        Random random = new Random(42);
        List<String> arrived = new ArrayList<>();
        List<String> admittedOrder = new ArrayList<>();
        Map<String, Long> lastRank = new HashMap<>();
        for (int step = 0; step < 300; step++) {
            int action = random.nextInt(3);
            if (action == 0 || arrived.isEmpty()) {
                String customer = "c" + step;
                enqueue(customer);
                arrived.add(customer);
            } else if (action == 1) {
                admit(1 + random.nextInt(3));
            } else {
                String customer = arrived.get(random.nextInt(arrived.size()));
                QueueStatus status = status(customer);
                if (status.entry().state() == QueueState.WAITING) {
                    Long previous = lastRank.put(customer, status.entry().rank());
                    assertThat(previous == null || status.entry().rank() <= previous)
                            .as("%s 의 앞 인원이 %s → %s", customer, previous, status.entry().rank()).isTrue();
                } else if (status.entry().state() == QueueState.ADMITTED && !admittedOrder.contains(customer)) {
                    admittedOrder.add(customer);
                }
            }
        }
        for (String customer : arrived) {
            if (status(customer).entry().state() == QueueState.ADMITTED && !admittedOrder.contains(customer)) {
                admittedOrder.add(customer);
            }
        }
        List<String> expected = arrived.subList(0, admittedOrder.size());
        assertThat(admittedOrder).as("입장한 사람은 먼저 온 사람부터 연속").containsExactlyInAnyOrderElementsOf(expected);
    }
}

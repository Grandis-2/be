package com.grandis.nova.waitingroom.redis;

import com.grandis.nova.waitingroom.domain.product.SalesWindow;
import com.grandis.nova.waitingroom.domain.queue.AdmissionTicket;
import com.grandis.nova.waitingroom.domain.queue.LeaveGrace;
import com.grandis.nova.waitingroom.domain.queue.QueueEntry;
import com.grandis.nova.waitingroom.domain.queue.QueueState;
import com.grandis.nova.waitingroom.redis.ControlStore.ApplyResult;
import com.grandis.nova.waitingroom.redis.ControlStore.ClusterView;
import com.grandis.nova.waitingroom.redis.ControlStore.LeaderLease;
import com.grandis.nova.waitingroom.redis.ControlStore.RelayOutcome;
import com.grandis.nova.waitingroom.redis.ControlStore.SettingAudit;
import com.grandis.nova.waitingroom.redis.ControlStore.SettingChange;
import com.grandis.nova.waitingroom.support.RedisContainer;
import com.grandis.nova.waitingroom.support.TestJwts;
import org.assertj.core.data.Offset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static com.grandis.nova.waitingroom.support.TestIds.productKey;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.entry;

class ControlStoreTest {

    static final String PRODUCT = productKey(101);
    static final Duration WAIT = Duration.ofSeconds(5);
    static final long FENCE_TTL = 60_000;
    static final SettingAudit AUDIT = new SettingAudit("admin", false, 10_000, Duration.ofDays(30));

    private ReactiveStringRedisTemplate redis;
    private ControlStore control;
    private QueueStore queue;
    private Instant now;

    @BeforeEach
    void setUp() {
        redis = RedisContainer.fresh();
        LuaScripts scripts = new LuaScripts(redis);
        control = new ControlStore(scripts, redis);
        queue = new QueueStore(scripts, AdmissionTicket.of(TestJwts.TOKEN_SECRET, List.of(), null));
        now = Instant.now();
    }

    private void enqueue(String... customers) {
        for (String customer : customers) {
            queue.enqueue(PRODUCT, customer, QueueStore.UNLIMITED, now, LeaveGrace.MIN).block(WAIT);
        }
    }

    @Nested
    class 리더 {

        @Test
        void 한_노드만_잡고_놓으면_다른_노드가_더_큰_임기로_잡는다() {
            LeaderLease a = control.acquireLeader("a", 2_000).block(WAIT);
            LeaderLease b = control.acquireLeader("b", 2_000).block(WAIT);
            LeaderLease renewed = control.acquireLeader("a", 2_000).block(WAIT);

            assertThat(a.acquired()).isTrue();
            assertThat(b.acquired()).isFalse();
            assertThat(b.owner()).isEqualTo("a");
            assertThat(renewed.fence()).as("연장은 같은 임기").isEqualTo(a.fence());

            assertThat(control.releaseLeader("b").block(WAIT)).as("남의 리스는 못 놓는다").isFalse();
            assertThat(control.releaseLeader("a").block(WAIT)).isTrue();
            LeaderLease next = control.acquireLeader("b", 2_000).block(WAIT);
            assertThat(next.acquired()).isTrue();
            assertThat(next.fence()).isGreaterThan(a.fence());
        }

        @Test
        void 임기_카운터가_정수_아닌_표기로_고쳐져도_리더를_뽑고_그_값_아래로_내려가지_않는다() {
            redis.opsForValue().set(RedisKeys.LEADER_GENERATION, "5e15").block(WAIT);

            LeaderLease next = control.acquireLeader("a", 2_000).block(WAIT);
            assertThat(next.acquired()).isTrue();
            assertThat(next.fence()).isEqualTo(5_000_000_000_000_001L);
        }

        @Test
        void 임기_카운터가_상한에_닿으면_낮춰_깔지_않고_선출을_멈춘다() {
            redis.opsForValue().set(RedisKeys.LEADER_GENERATION, "9007199254740991").block(WAIT);

            assertThatThrownBy(() -> control.acquireLeader("a", 2_000).block(WAIT)).rootCause().hasMessageContaining("상한");
            assertThat(redis.opsForValue().get(RedisKeys.LEADER_GENERATION).block(WAIT)).isEqualTo("9007199254740991");
            assertThat(redis.hasKey(RedisKeys.LEADER).block(WAIT)).isFalse();
        }

        @Test
        void 임기_카운터를_잃어도_이전_임기보다_작아지지_않는다() {
            long first = control.acquireLeader("a", 2_000).block(WAIT).fence();
            control.releaseLeader("a").block(WAIT);
            redis.delete(RedisKeys.LEADER_GENERATION).block(WAIT);

            assertThat(control.acquireLeader("b", 2_000).block(WAIT).fence()).isGreaterThan(first);
        }
    }

    @Nested
    class 입장_커서 {

        @Test
        void 몫만큼_올리고_뒤로_가지_않는다() {
            enqueue("a", "b", "c");

            ApplyResult first = control.apply(PRODUCT, 2, 10, FENCE_TTL, -1, 1).block(WAIT);
            ApplyResult none = control.apply(PRODUCT, 0, 10, FENCE_TTL, -1, 2).block(WAIT);

            assertThat(first.entered()).isEqualTo(2);
            assertThat(none.cursor()).isEqualTo(first.cursor());
            assertThat(queue.status(PRODUCT, "b", now, LeaveGrace.MIN).block(WAIT).entry().state()).isEqualTo(QueueState.ADMITTED);
            assertThat(queue.status(PRODUCT, "c", now, LeaveGrace.MIN).block(WAIT).entry().state()).isEqualTo(QueueState.WAITING);
        }

        @Test
        void 줄보다_큰_몫이어도_맨_뒤_사람까지만이고_뒤에_온_사람은_커서_위에_선다() {
            enqueue("a");
            control.apply(PRODUCT, 100, 10, FENCE_TTL, -1, 3).block(WAIT);
            enqueue("late");

            assertThat(queue.status(PRODUCT, "late", now, LeaveGrace.MIN).block(WAIT).entry().state()).isEqualTo(QueueState.WAITING);
        }

        @Test
        void 옛_임기의_리더는_커서를_올리지_못한다() {
            enqueue("a", "b");
            control.apply(PRODUCT, 1, 20, FENCE_TTL, -1, 4).block(WAIT);

            assertThat(control.apply(PRODUCT, 1, 19, FENCE_TTL, -1, 5).block(WAIT).fenced()).isTrue();
            assertThat(control.apply(PRODUCT, 1, 0, FENCE_TTL, -1, 6).block(WAIT).fenced()).as("리더가 아니다").isTrue();
            assertThat(queue.status(PRODUCT, "b", now, LeaveGrace.MIN).block(WAIT).entry().state()).isEqualTo(QueueState.WAITING);
        }

        @Test
        void 같은_임기에서_이미_적용한_회차는_재시도해도_다시_올리지_않고_새_임기는_회차가_작아도_막지_않는다() {
            enqueue("a", "b", "c", "d");
            ApplyResult first = control.apply(PRODUCT, 1, 10, FENCE_TTL, -1, 500).block(WAIT);

            ApplyResult retried = control.apply(PRODUCT, 1, 10, FENCE_TTL, -1, 500).block(WAIT);
            ApplyResult older = control.apply(PRODUCT, 1, 10, FENCE_TTL, -1, 499).block(WAIT);
            assertThat(first.applied()).isTrue();
            assertThat(retried.applied()).isFalse();
            assertThat(retried.entered()).isZero();
            assertThat(retried.cursor()).isEqualTo(first.cursor());
            assertThat(older.applied()).as("앞 회차는 거부").isFalse();

            assertThat(control.apply(PRODUCT, 1, 10, FENCE_TTL, -1, 501).block(WAIT).entered()).isEqualTo(1);
            assertThat(control.apply(PRODUCT, 1, 11, FENCE_TTL, -1, 400).block(WAIT).entered())
                    .as("새 임기는 회차가 작아도 들인다").isEqualTo(1);
            assertThat(queue.status(PRODUCT, "d", now, LeaveGrace.MIN).block(WAIT).entry().state()).isEqualTo(QueueState.WAITING);
        }

        @Test
        void 같은_회차를_동시에_여러_번_보내도_한_번만_올린다() {
            enqueue("a", "b", "c");

            List<ApplyResult> results = Flux.range(0, 16)
                    .flatMap(i -> control.apply(PRODUCT, 1, 10, FENCE_TTL, -1, 600), 16)
                    .collectList().block(WAIT);

            assertThat(results).filteredOn(ApplyResult::applied).hasSize(1);
            assertThat(results).extracting(ApplyResult::entered).containsOnly(0L, 1L);
            assertThat(queue.status(PRODUCT, "a", now, LeaveGrace.MIN).block(WAIT).entry().state()).isEqualTo(QueueState.ADMITTED);
            assertThat(queue.status(PRODUCT, "b", now, LeaveGrace.MIN).block(WAIT).entry().state()).isEqualTo(QueueState.WAITING);
        }

        @Test
        void 줄_조회가_실패하면_회차를_쓴_것으로_남기지_않아_고친_뒤_같은_회차로_다시_들인다() {
            redis.opsForValue().set("wr:queue:{" + PRODUCT + "}", "broken").block(WAIT);
            assertThatThrownBy(() -> control.apply(PRODUCT, 1, 10, FENCE_TTL, -1, 700).block(WAIT));

            redis.delete("wr:queue:{" + PRODUCT + "}").block(WAIT);
            enqueue("a");
            assertThat(control.apply(PRODUCT, 1, 10, FENCE_TTL, -1, 700).block(WAIT).entered()).isEqualTo(1);
        }

        @Test
        void 커서가_사라지면_이_리더가_본_값으로_되살려_들인_사람이_대기로_돌아가지_않는다() {
            enqueue("a", "b");
            ApplyResult applied = control.apply(PRODUCT, 1, 10, FENCE_TTL, -1, 7).block(WAIT);
            redis.delete("wr:admitted:{" + PRODUCT + "}").block(WAIT);

            ApplyResult healed = control.apply(PRODUCT, 0, 10, FENCE_TTL, applied.cursor(), 8).block(WAIT);

            assertThat(healed.cursor()).isEqualTo(applied.cursor());
            assertThat(queue.status(PRODUCT, "a", now, LeaveGrace.MIN).block(WAIT).entry().state()).isEqualTo(QueueState.ADMITTED);
        }
    }

    @Nested
    class 회차_일정 {

        private final SalesWindow window = new SalesWindow(Instant.parse("2026-10-10T01:00:00Z"),
                Instant.parse("2026-10-10T02:00:00Z"));
        private final SalesWindow moved = new SalesWindow(Instant.parse("2026-10-11T01:00:00Z"),
                Instant.parse("2026-10-11T02:00:00Z"));

        @Test
        void 일정_번호가_클_때만_쓰고_같거나_옛_번호는_버린다() {
            assertThat(control.applySchedule(PRODUCT, window, 2, true).block(WAIT)).isTrue();
            assertThat(control.applySchedule(PRODUCT, moved, 2, true).block(WAIT)).as("재전송").isFalse();
            assertThat(control.applySchedule(PRODUCT, moved, 1, true).block(WAIT)).as("늦게 온 옛 일정").isFalse();
            assertThat(products()).isEqualTo(ProductSchedules.format(window, 2, true));

            assertThat(control.applySchedule(PRODUCT, moved, 3, true).block(WAIT)).isTrue();
            assertThat(products()).isEqualTo(ProductSchedules.format(moved, 3, true));
        }

        @Test
        void 비공개_일정도_번호를_마지막_칸에_두어_같은_순서_판정을_받는다() {
            assertThat(control.applySchedule(PRODUCT, window, 2, false).block(WAIT)).isTrue();
            assertThat(products()).isEqualTo(window.opensAt().toEpochMilli() + "|" + window.closesAt().toEpochMilli()
                    + "|hidden|2");
            assertThat(ProductSchedules.parse(products())).hasValue(new ProductSchedules.Schedule(window, 2, false));

            assertThat(control.applySchedule(PRODUCT, window, 1, true).block(WAIT)).as("늦게 온 옛 공개").isFalse();
            assertThat(control.applySchedule(PRODUCT, window, 3, true).block(WAIT)).isTrue();
            assertThat(ProductSchedules.parse(products())).hasValue(new ProductSchedules.Schedule(window, 3, true));
        }

        @Test
        void 번호를_읽을_수_없는_값은_새_일정으로_덮는다() {
            redis.opsForHash().put(RedisKeys.PRODUCTS, PRODUCT, "broken").block(WAIT);

            assertThat(control.applySchedule(PRODUCT, window, 1, true).block(WAIT)).isTrue();
        }

        @Test
        void 은퇴_표식은_일정_번호를_남겨_재전달된_옛_일정이_끝난_회차를_다시_열지_못한다() {
            control.applySchedule(PRODUCT, window, 2, true).block(WAIT);
            String seen = products();

            assertThat(control.retireSchedule(PRODUCT, seen, 2, Instant.ofEpochMilli(1_000)).block(WAIT)).isTrue();
            assertThat(products()).isEqualTo("retired|2|1000");
            assertThat(control.applySchedule(PRODUCT, moved, 1, true).block(WAIT)).as("옛 번호").isFalse();
            assertThat(control.applySchedule(PRODUCT, moved, 2, true).block(WAIT)).as("같은 번호").isFalse();
            assertThat(control.applySchedule(PRODUCT, moved, 3, true).block(WAIT)).as("새 일정은 받는다").isTrue();
        }

        @Test
        void 읽은_뒤_새_일정이_왔으면_은퇴시키지도_지우지도_않는다() {
            control.applySchedule(PRODUCT, window, 1, true).block(WAIT);
            String seen = products();
            control.applySchedule(PRODUCT, moved, 2, true).block(WAIT);

            assertThat(control.retireSchedule(PRODUCT, seen, 1, Instant.now()).block(WAIT)).isFalse();
            assertThat(control.dropSchedule(PRODUCT, seen).block(WAIT)).isFalse();
            assertThat(control.dropSchedule(PRODUCT, products()).block(WAIT)).isTrue();
            assertThat(products()).isNull();
        }

        @Test
        void 같은_번호가_동시에_여러_번_와도_한_번만_쓰고_섞여_와도_가장_큰_번호가_남는다() {
            List<Boolean> same = Flux.range(0, 8)
                    .flatMap(i -> control.applySchedule(PRODUCT, window, 3, true), 8)
                    .collectList().block(WAIT);
            assertThat(same).filteredOn(Boolean::booleanValue).hasSize(1);

            Flux.just(5L, 4L, 5L, 2L, 4L, 5L)
                    .flatMap(version -> control.applySchedule(PRODUCT, moved, version, true), 6)
                    .collectList().block(WAIT);
            assertThat(products()).isEqualTo(ProductSchedules.format(moved, 5, true));
        }

        @Test
        void 재발행_선점은_한_노드만_잡고_남의_선점은_풀지_않는다() {
            String marker = RedisKeys.RESYNC_REQUESTED;
            assertThat(control.claimResync(marker, "a", Duration.ofSeconds(60)).block(WAIT)).isTrue();
            assertThat(control.claimResync(marker, "b", Duration.ofSeconds(60)).block(WAIT)).isFalse();

            assertThat(control.releaseResyncClaim(marker, "b").block(WAIT)).isFalse();
            assertThat(control.releaseResyncClaim(marker, "a").block(WAIT)).isTrue();
            assertThat(control.claimResync(marker, "b", Duration.ofSeconds(60)).block(WAIT)).isTrue();
            assertThat(control.claimResync(RedisKeys.RESYNC_PERIODIC, "c", Duration.ofSeconds(60)).block(WAIT))
                    .as("주기 표식은 따로 선점한다").isTrue();
        }

        @Test
        void 일정_번호_0_은_계약_위반이라_거절한다() {
            assertThatThrownBy(() -> control.applySchedule(PRODUCT, window, 0, true).block(WAIT)).rootCause()
                    .hasMessageContaining("1 이상");
        }

        @Test
        void 재발행_요청_표식은_보낸_뒤에_세우고_기간이_지나면_사라진다() {
            assertThat(redis.hasKey(RedisKeys.RESYNC_REQUESTED).block(WAIT)).isFalse();
            control.markResyncRequested(RedisKeys.RESYNC_REQUESTED, Duration.ofSeconds(60)).block(WAIT);

            assertThat(redis.hasKey(RedisKeys.RESYNC_REQUESTED).block(WAIT)).isTrue();
            assertThat(redis.getExpire(RedisKeys.RESYNC_REQUESTED).block(WAIT)).isPositive();
        }

        private String products() {
            return (String) redis.opsForHash().get(RedisKeys.PRODUCTS, PRODUCT).block(WAIT);
        }
    }

    @Nested
    class 운영값 {

        @Test
        void 동시에_바꿔도_돌려받은_전_값들이_한_줄로_이어져_감사_로그가_빠짐없다() {
            List<String> values = IntStream.rangeClosed(1, 16).mapToObj(String::valueOf).toList();

            List<Optional<String>> befores = Flux.fromIterable(values)
                    .flatMap(value -> control.writeSetting("global-credit", value, AUDIT), values.size())
                    .collectList().block(WAIT);
            String last = (String) redis.opsForHash().get(RedisKeys.SETTINGS, "global-credit").block(WAIT);

            // 원자적으로 바꿨다면 전 값은 "없음" 하나와 서로 다른 값들이고, 그 값들과 최종 값을 합치면 보낸 값 전부다
            assertThat(befores).filteredOn(Optional::isEmpty).hasSize(1);
            List<String> seen = new ArrayList<>(befores.stream().flatMap(Optional::stream).toList());
            assertThat(seen).doesNotHaveDuplicates().doesNotContain(last);
            seen.add(last);
            assertThat(seen).containsExactlyInAnyOrderElementsOf(values);
        }

        @Test
        void 읽은_때_값이_그대로일_때만_바꾸고_그사이_바뀌었으면_쓰지_않는다() {
            assertThat(control.compareAndSetSetting("global-credit", Optional.empty(), "200", AUDIT).blockOptional(WAIT))
                    .as("없던 칸 — 바꿨고 전 값은 없다").contains(Optional.empty());
            assertThat(control.compareAndSetSetting("global-credit", Optional.empty(), "300", AUDIT).blockOptional(WAIT))
                    .as("그사이 200 이 들어왔다").isEmpty();
            assertThat(control.compareAndSetSetting("global-credit", Optional.of("100"), "300", AUDIT).blockOptional(WAIT)).isEmpty();
            assertThat(control.compareAndSetSetting("global-credit", Optional.of("200"), "300",
                    new SettingAudit("admin", true, 10_000, Duration.ofDays(30))).blockOptional(WAIT))
                    .contains(Optional.of("200"));

            assertThat(redis.opsForHash().get(RedisKeys.SETTINGS, "global-credit").block(WAIT)).isEqualTo("300");
            assertThat(changes(10)).as("바꾸지 못한 시도는 이력에 남지 않는다").extracting(SettingChange::fields).containsExactly(
                    Map.of("by", "admin", "field", "global-credit", "confirmed", "1", "from", "200", "to", "300"),
                    Map.of("by", "admin", "field", "global-credit", "confirmed", "0", "to", "200"));
        }

        @Test
        void 지우면_지운_값을_돌려주고_없던_칸은_빈_값이다() {
            control.writeSetting("max-wait-sec", "60", AUDIT).block(WAIT);

            assertThat(control.clearSetting("max-wait-sec", AUDIT).block(WAIT)).contains("60");
            assertThat(control.clearSetting("max-wait-sec", AUDIT).block(WAIT)).isEmpty();
            assertThat(changes(10)).as("없던 칸을 지운 것은 바뀐 것이 아니다").extracting(SettingChange::fields).containsExactly(
                    Map.of("by", "admin", "field", "max-wait-sec", "confirmed", "0", "from", "60"),
                    Map.of("by", "admin", "field", "max-wait-sec", "confirmed", "0", "to", "60"));
        }

        @Test
        void 같은_값으로_다시_쓰면_이력을_남기지_않는다() {
            control.writeSetting("max-wait-sec", "60", AUDIT).block(WAIT);
            assertThat(control.writeSetting("max-wait-sec", "60", AUDIT).block(WAIT)).contains("60");
            assertThat(control.compareAndSetSetting("max-wait-sec", Optional.of("60"), "60", AUDIT).blockOptional(WAIT))
                    .as("읽은 값 그대로라 성공으로 답한다").contains(Optional.of("60"));

            assertThat(changes(10)).hasSize(1);
        }

        @Test
        void 이력_키를_쓸_수_없으면_운영값도_바꾸지_않는다() {
            control.writeSetting("max-wait-sec", "60", AUDIT).block(WAIT);
            redis.delete(RedisKeys.SETTING_CHANGES).block(WAIT);
            redis.opsForValue().set(RedisKeys.SETTING_CHANGES, "broken").block(WAIT);

            assertThatThrownBy(() -> control.writeSetting("max-wait-sec", "120", AUDIT).block(WAIT))
                    .hasRootCauseMessage("변경 이력 키가 스트림이 아니다: string");
            assertThatThrownBy(() -> control.clearSetting("max-wait-sec", AUDIT).block(WAIT))
                    .hasRootCauseMessage("변경 이력 키가 스트림이 아니다: string");
            assertThatThrownBy(() -> control.compareAndSetSetting("max-wait-sec", Optional.of("60"), "120", AUDIT).block(WAIT))
                    .hasRootCauseMessage("변경 이력 키가 스트림이 아니다: string");
            assertThat(redis.opsForHash().get(RedisKeys.SETTINGS, "max-wait-sec").block(WAIT)).isEqualTo("60");
        }

        @Test
        void 이력은_최대_건수를_넘으면_오래된_것부터_지운다() {
            SettingAudit three = new SettingAudit("admin", false, 3, Duration.ofDays(30));
            for (int value = 1; value <= 5; value++) {
                control.writeSetting("global-credit", String.valueOf(value), three).block(WAIT);
            }

            assertThat(changes(10)).extracting(change -> change.fields().get("to")).containsExactly("5", "4", "3");
        }

        @Test
        void 이력은_보존_기간이_지난_것을_다음_변경_때_지운다() {
            addChange(Instant.now().minus(Duration.ofDays(31)).toEpochMilli(), "1");

            control.writeSetting("global-credit", "2", AUDIT).block(WAIT);

            List<SettingChange> left = changes(10);
            assertThat(left).extracting(change -> change.fields().get("to")).containsExactly("2");
            assertThat(Duration.between(left.getFirst().at(), Instant.now()).abs()).as("Redis 시각으로 남긴다")
                    .isLessThan(Duration.ofSeconds(5));
        }

        @Test
        void 이력은_최신부터_기간_안에서_커서_앞의_것만_읽는다() {
            for (int value = 1; value <= 4; value++) {
                addChange(value * 1_000L, String.valueOf(value));
            }

            assertThat(changes(10)).extracting(change -> change.fields().get("to")).containsExactly("4", "3", "2", "1");
            assertThat(changes(2)).extracting(SettingChange::id).containsExactly("4000-0", "3000-0");
            assertThat(changes(10).getFirst().at()).isEqualTo(Instant.ofEpochMilli(4_000));
            assertThat(control.readSettingChanges(null, null, "3000-0", 10).block(WAIT))
                    .as("커서는 빼고 그 앞부터").extracting(SettingChange::id).containsExactly("2000-0", "1000-0");
            assertThat(control.readSettingChanges(Instant.ofEpochMilli(2_000), Instant.ofEpochMilli(3_000), null, 10).block(WAIT))
                    .as("기간 양 끝 포함").extracting(SettingChange::id).containsExactly("3000-0", "2000-0");
            assertThat(control.readSettingChanges(Instant.ofEpochMilli(2_000), null, "3000-0", 10).block(WAIT))
                    .extracting(SettingChange::id).containsExactly("2000-0");
            assertThat(control.readSettingChanges(Instant.ofEpochMilli(2_000).plusNanos(1), null, null, 10).block(WAIT))
                    .as("밀리초 아래가 있는 시작 시각보다 이른 변경은 뺀다").extracting(SettingChange::id)
                    .containsExactly("4000-0", "3000-0");
        }

        private void addChange(long millis, String to) {
            redis.opsForStream().add(StreamRecords.string(Map.of("by", "admin", "field", "global-credit", "confirmed", "0", "to", to))
                    .withStreamKey(RedisKeys.SETTING_CHANGES).withId(RecordId.of(millis, 0))).block(WAIT);
        }

        private List<SettingChange> changes(int count) {
            return control.readSettingChanges(null, null, null, count).block(WAIT);
        }
    }

    @Nested
    class 판정_재료 {

        @Test
        void 발행한_필드만_남기고_옛_임기의_발행은_막는다() {
            assertThat(control.publishSnapshot(5, FENCE_TTL, Map.of("p:" + productKey(1), "x", "p:" + productKey(2), "y")).block(WAIT)).isTrue();
            assertThat(control.publishSnapshot(6, FENCE_TTL, Map.of("p:" + productKey(1), "z")).block(WAIT)).isTrue();
            assertThat(control.publishSnapshot(5, FENCE_TTL, Map.of("p:" + productKey(9), "old")).block(WAIT)).isFalse();

            ControlStore.TimedEntries read = control.readSnapshot().block(WAIT);
            assertThat(read.entries()).containsExactly(Map.entry("p:" + productKey(1), "z"));
            assertThat(read.redisNowMillis()).isCloseTo(System.currentTimeMillis(), Offset.offset(5_000L));
        }

        @Test
        void 모델이_많아도_한_번에_발행한다() {
            Map<String, String> fields = IntStream.range(0, 5_000).boxed()
                    .collect(Collectors.toMap(i -> "p:" + i, String::valueOf));

            assertThat(control.publishSnapshot(5, FENCE_TTL, fields).block(WAIT)).isTrue();
            assertThat(control.readSnapshot().block(WAIT).entries()).hasSize(5_000);
        }
    }

    @Nested
    class 하트비트 {

        @Test
        void 살아_있는_노드와_한산_통과_합_노드별_전달_누적을_돌려주고_죽은_노드는_지운다() {
            control.heartbeat("a", 5, 2, 3, new RelayOutcome(10, 2)).block(WAIT);
            redis.opsForHash().put(RedisKeys.GATEWAYS, "dead", "1").block(WAIT);
            redis.opsForHash().put(RedisKeys.GATEWAYS, "#r:dead", "99|99").block(WAIT);
            ClusterView view = control.heartbeat("b", 5, 2, 4, new RelayOutcome(9_000_000_000_000_000L, 1)).block(WAIT);

            assertThat(view.aliveGateways()).isEqualTo(2);
            assertThat(view.idlePassSum()).isEqualTo(7);
            assertThat(view.relayTotals()).as("큰 누적도 지수 표기 없이 그대로")
                    .containsOnly(entry("a", new RelayOutcome(10, 2)), entry("b", new RelayOutcome(9_000_000_000_000_000L, 1)));
            assertThat(redis.opsForHash().hasKey(RedisKeys.GATEWAYS, "dead").block(WAIT)).isFalse();
            assertThat(redis.opsForHash().hasKey(RedisKeys.GATEWAYS, "#r:dead").block(WAIT)).isFalse();

            control.leave("a").block(WAIT);
            assertThat(redis.opsForHash().hasKey(RedisKeys.GATEWAYS, "#r:a").block(WAIT)).isFalse();
            assertThat(control.heartbeat("b", 5, 2, 0, RelayOutcome.NONE).block(WAIT).aliveGateways()).isEqualTo(1);
        }

        @Test
        void 나쁜_응답이_전달보다_많으면_계약_위반이라_거절한다() {
            assertThatThrownBy(() -> control.heartbeat("a", 5, 2, 0, new RelayOutcome(1, 2)).block(WAIT)).rootCause()
                    .hasMessageContaining("나쁨");
        }
    }

    @Nested
    class 청소 {

        @Test
        void 신호가_끊긴_대기자는_이탈로_옮기고_다시_서면_재방문으로_맨_뒤에_선다() {
            enqueue("a", "b");
            long later = now.getEpochSecond() + 1_000;

            ControlStore.SweepResult result = control.sweep(PRODUCT, 100, later, 3_000, 100, "0", true, 1).block(WAIT);
            // 지금(later) 뒤로 살아 있는 신호가 하나도 없으면 아무도 빼지 않는다 — b 의 신호를 살린다
            assertThat(result.swept()).isZero();

            queue.status(PRODUCT, "b", Instant.ofEpochSecond(later), LeaveGrace.MIN).block(WAIT);
            result = control.sweep(PRODUCT, 100, later, 3_000, 100, "0", true, 1).block(WAIT);
            assertThat(result.swept()).isEqualTo(1);
            assertThat(queue.status(PRODUCT, "a", Instant.ofEpochSecond(later), LeaveGrace.MIN).block(WAIT).entry().state())
                    .isEqualTo(QueueState.NOT_QUEUED);

            QueueEntry back = queue.enqueue(PRODUCT, "a", QueueStore.UNLIMITED, Instant.ofEpochSecond(later), LeaveGrace.MIN).block(WAIT).entry();
            assertThat(back.rejoined()).isTrue();
            assertThat(back.rank()).as("떠난 사이 온 사람을 추월하지 않는다").isEqualTo(1);
        }
    }

    @Nested
    class 마감_정리 {

        @Test
        void 표를_세운_다음_회차에_시각이_지났을_때만_줄을_지운다() {
            enqueue("a");
            long past = now.getEpochSecond() - 10;
            long future = now.getEpochSecond() + 3_600;

            assertThat(control.closeQueue(PRODUCT, 7, true, FENCE_TTL, past, 600).block(WAIT)).as("표가 없다").isEqualTo(-2);
            assertThat(control.closeQueue(PRODUCT, 7, true, FENCE_TTL, future, 600).block(WAIT)).as("아직 유예").isZero();
            assertThat(control.closeQueue(PRODUCT, 6, true, FENCE_TTL, past, 600).block(WAIT)).as("옛 임기").isEqualTo(-1);
            assertThat(control.closeQueue(PRODUCT, 7, true, FENCE_TTL, past, 600).block(WAIT)).as("청소가 걷지 않은 생존 신호").isZero();
            redis.opsForZSet().remove("wr:alive:{" + PRODUCT + "}", "a").block(WAIT);
            assertThat(control.closeQueue(PRODUCT, 7, true, FENCE_TTL, past, 600).block(WAIT)).isEqualTo(1);
            assertThat(redis.hasKey("wr:queue:{" + PRODUCT + "}").block(WAIT)).isFalse();
        }
    }
}

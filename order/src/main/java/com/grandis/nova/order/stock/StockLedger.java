package com.grandis.nova.order.stock;

import com.grandis.nova.order.stock.domain.exception.StockAlreadyCreatedException;
import com.grandis.nova.order.stock.domain.exception.StockBelowCommittedException;
import com.grandis.nova.order.stock.domain.exception.StockShortageException;
import com.grandis.nova.order.stock.domain.model.StockLevel;
import com.grandis.nova.order.stock.domain.model.StockSetting;
import com.grandis.nova.order.stock.domain.repository.StockReader;
import com.grandis.nova.order.stock.domain.repository.StockWriter;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 재고 표를 바꾸는 유일한 길. 쓰기 포트({@link StockWriter})는 여기서만 쓴다(StockArchitectureTest 가 강제).
 * 모든 변경은 조건부 UPDATE 이고 영향 행으로 판정한다.
 *
 * 여러 옵션의 처리(option_id 오름차순):
 * <ul>
 *   <li>설정({@link #set}): 있는 행을 잠가 읽기 → 있으면 조건부 UPDATE, 없으면 INSERT.</li>
 *   <li>초기화({@link #initialize}): 있는 행을 잠그지 않고 읽기 → 없는 것만 INSERT. 있는 행은 건드리지 않으므로 잠그지 않는다 —
 *       재고 행은 주문의 확보 · 반환이 드나드는 자리라 쓰지 않을 행을 붙잡지 않는다.</li>
 *   <li>확보({@link #reserve}): 옵션마다 조건부 UPDATE 한 번(가용 ≥ 수량일 때만 확보 += 수량). 읽고 계산하지 않는다.</li>
 * </ul>
 * 잠금 순서가 요청 순서와 무관해, 옵션 순서가 다른 요청끼리 서로 기다리며 교착하지 않는다. 동시 삽입이 부르는 잠금
 * (PK 중복 확인의 S 잠금, FK 검사의 product_options 부모 S 잠금)은 이 순서 밖이라 드물게 교착할 수 있고, 그건 호출하는
 * 쪽의 다시 하기가 흡수한다.
 *
 * <b>트랜잭션 계약</b>
 * <ul>
 *   <li>스스로 트랜잭션을 열지 않는다(MANDATORY). 여러 옵션이 모두 되거나 모두 안 돼야 해서다.</li>
 *   <li>여기서 나가는 예외는 모두 호출자의 트랜잭션을 롤백해야 한다. 잡아서 이어 쓰지 않는다.</li>
 *   <li>{@link StockAlreadyCreatedException}: 없다고 본 옵션을 다른 트랜잭션이 먼저 만들었다. READ COMMITTED 에서 없는 행은
 *       잠기지 않으므로 동시 생성은 교착이 아니라 PK 중복으로 드러난다. 새 트랜잭션에서 다시 하면 "있는 행"으로 잡힌다.</li>
 * </ul>
 */
@Component
@Transactional(propagation = Propagation.MANDATORY)
public class StockLedger {

    private final StockWriter writer;
    private final StockReader reader;
    private final Clock clock;

    public StockLedger(StockWriter writer, StockReader reader, Clock clock) {
        this.writer = writer;
        this.reader = reader;
        this.clock = clock;
    }

    /**
     * 총량을 설정한다. 행이 없으면 확보 · 판매 0 으로 만든다.
     *
     * @return 이번에 새로 만든 행의 옵션 id
     * @throws StockBelowCommittedException 확보 + 판매보다 작게 줄이려 한 옵션이 있다. 아무것도 반영하지 않아야 한다(롤백)
     * @throws IllegalArgumentException     같은 옵션이 두 번 있다 — 호출하는 코드의 잘못이다
     */
    public Set<UUID> set(List<StockSetting> settings) {
        List<StockSetting> sorted = ascending(settings);
        return apply(sorted, byOptionId(writer.lockByOptionIds(optionIds(sorted))), true);
    }

    /**
     * 행이 없는 옵션만 만든다. 있는 행은 값이 달라도 건드리지 않는다 — 상품 등록의 재개가 그사이 관리자가 고친 재고를
     * 등록 때의 초기값으로 덮지 않게 하려는 것이다.
     *
     * @return 이번에 새로 만든 행의 옵션 id
     * @throws IllegalArgumentException 같은 옵션이 두 번 있다 — 호출하는 코드의 잘못이다
     */
    public Set<UUID> initialize(List<StockSetting> settings) {
        List<StockSetting> sorted = ascending(settings);
        return apply(sorted, byOptionId(reader.findByOptionIds(optionIds(sorted))), false);
    }

    /**
     * 주문의 재고 확보. 옵션 id 오름차순으로 하나씩 조건부 UPDATE 하고, 하나라도 0행이면 걸린 옵션을 모두 모아 던진다 —
     * 호출자가 롤백하므로 먼저 확보한 옵션도 되돌아간다(전량 확보 아니면 아무것도 확보하지 않음).
     *
     * @param quantities 옵션 id → 확보할 수량(1 이상)
     * @throws StockShortageException 가용 재고가 모자란 옵션이 있다(재고 행이 없는 옵션 포함)
     */
    public void reserve(Map<UUID, Integer> quantities) {
        Instant now = clock.instant();
        List<UUID> shortages = new ArrayList<>();
        for (Map.Entry<UUID, Integer> entry : new TreeMap<>(quantities).entrySet()) {
            if (entry.getValue() < 1) {
                throw new IllegalArgumentException("확보 수량은 1 이상이다: " + entry);
            }
            if (writer.reserve(entry.getKey(), entry.getValue(), now) != 1) {
                shortages.add(entry.getKey());
            }
        }
        if (!shortages.isEmpty()) {
            throw new StockShortageException(shortages);
        }
    }

    /** @param existing 이미 읽은(설정이면 잠근) 행. 그 뒤의 시각을 찍어야 기다린 다른 쓰기보다 앞선 시각이 남지 않는다 */
    private Set<UUID> apply(List<StockSetting> sorted, Map<UUID, StockLevel> existing, boolean overwrite) {
        Instant now = clock.instant();
        Set<UUID> created = new HashSet<>();
        List<StockBelowCommittedException.Shortfall> shortfalls = new ArrayList<>();
        for (StockSetting setting : sorted) {
            StockLevel current = existing.get(setting.optionId());
            if (current == null) {
                writer.insert(setting.optionId(), setting.total(), now);
                created.add(setting.optionId());
            } else if (overwrite && writer.changeTotal(setting.optionId(), setting.total(), now) != 1) {
                // 행을 잠근 채 판정하므로 0 이면 원인은 감소 한계뿐이다. 잠근 값과 어긋나면 잠금 규칙이 깨진 것이다.
                if (current.committed() <= setting.total()) {
                    throw new IllegalStateException("잠근 재고 행이 바뀌었다: optionId=" + setting.optionId());
                }
                shortfalls.add(new StockBelowCommittedException.Shortfall(setting.optionId(), current.committed()));
            }
        }
        if (!shortfalls.isEmpty()) {
            throw new StockBelowCommittedException(shortfalls);
        }
        return created;
    }

    private static List<UUID> optionIds(List<StockSetting> sorted) {
        return sorted.stream().map(StockSetting::optionId).toList();
    }

    private static Map<UUID, StockLevel> byOptionId(List<StockLevel> levels) {
        return levels.stream().collect(Collectors.toMap(StockLevel::optionId, Function.identity()));
    }

    private static List<StockSetting> ascending(List<StockSetting> settings) {
        if (settings.stream().map(StockSetting::optionId).distinct().count() != settings.size()) {
            throw new IllegalArgumentException("같은 옵션이 두 번 있다: " + settings);
        }
        return settings.stream()
                .sorted(Comparator.comparing(StockSetting::optionId))
                .toList();
    }
}

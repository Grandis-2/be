package com.grandis.nova.catalog.image;

import com.grandis.nova.catalog.web.ValidationFailures;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

/**
 * 사진 붙이기 · 떼기. 사진 주소를 바꾸는 저장은 모두 이것 하나를 부른다 — 경로마다 따로 하면 하나를 빠뜨렸을 때 그 사진이 조용히 UNUSED 로 남아
 * 정리 배치가 쓰는 사진을 지운다. 저장과 같은 트랜잭션에서 부른다(저장이 롤백되면 상태도 그대로).
 *
 * 비교는 주소가 아니라 객체 키(URI 경로)로 한다 — CDN 도메인이 바뀐 뒤 옛 주소를 새 주소로 바꿔 저장해도 같은 사진이다.
 * <ul>
 *   <li>새로 쓰는 사진: 주소가 업로드 API 가 준 모양 그대로(CDN 주소 + 키 — 쿼리 · 조각 · 다른 호스트 없음)이고 키가 용도 접두(products/ ·
 *       promotions/)로 시작하며 표에 UNUSED 여야 한다. 아니면 그 칸의 400.
 *       한 사진은 한 곳에만 붙는다 — USED 인 사진(다른 상품 · 홍보가 쓴다)은 받지 않는다.</li>
 *   <li>빠진 사진(이전 키 중 이후 문서의 어느 주소에도 없는 키): 표에 USED 로 있으면 UNUSED 로. 표에 없는 주소(시드 · 이관 전 주소 · CDN 밖)는 무시한다.</li>
 *   <li>그대로인 사진은 보지 않는다 — CDN 이 아닌 옛 주소도 다시 저장할 때 막히지 않는다.</li>
 * </ul>
 * 잠금은 새 사진과 빠진 사진의 id 를 합쳐 한 문장으로 건다({@link ImageStore#lock}).
 */
@Component
public class ImageAttachments {

    private final ImageStore store;
    private final String cdnPrefix;
    private final Clock clock;

    public ImageAttachments(ImageStore store, ImageUploadProperties properties, Clock clock) {
        this.store = store;
        this.cdnPrefix = properties.imageUrlPrefix();
        this.clock = clock;
    }

    /**
     * @param before  저장 전 문서의 사진 주소(새 문서면 빈 목록)
     * @param after   저장할 문서의 사진 주소
     * @param fieldOf 새 주소 → 400 의 칸 이름
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void sync(ImagePurpose purpose, Collection<String> before, Collection<String> after, Function<String, String> fieldOf) {
        Set<String> beforeUrls = new LinkedHashSet<>(before);
        Map<String, String> beforeKeys = new LinkedHashMap<>();  // 키 → 주소
        for (String url : beforeUrls) {
            ImageKeys.keyOf(url).ifPresent(key -> beforeKeys.putIfAbsent(key, url));
        }
        // 뗄 것은 "이전 키 − 이후 키 전부" 다 — 그대로 둔 주소의 키도 이후 키에 넣는다. 같은 사진을 두 모양(쿼리 · 다른 도메인)으로 든 옛 문서에서
        // 한 모양만 빼도 사진은 아직 쓰인다
        Set<String> afterKeys = new HashSet<>();
        Map<UUID, Attach> attach = new LinkedHashMap<>();
        for (String url : new LinkedHashSet<>(after)) {
            Optional<String> key = ImageKeys.keyOf(url);
            if (beforeUrls.contains(url)) {
                key.ifPresent(afterKeys::add);  // 그대로인 주소는 보지 않는다(시드 · CDN 밖 옛 주소도 다시 저장된다)
                continue;
            }
            String field = fieldOf.apply(url);
            // 새 주소는 정본 모양만 — CDN 주소 + 키 그대로(쿼리 · 조각 · 다른 호스트 없음). 같은 사진이 여러 모양으로 문서에 들어가지 않고,
            // 바깥 호스트 주소가 같은 키를 달고 들어오지 못한다
            if (key.isEmpty() || !url.equals(cdnPrefix + key.get())) {
                throw ValidationFailures.of(field, "사진은 업로드 API 가 준 주소(" + cdnPrefix + purpose.keyPrefix() + "…) 그대로만 받습니다.");
            }
            afterKeys.add(key.get());
            if (beforeKeys.containsKey(key.get())) {
                continue;  // 이미 이 문서가 쓰는 사진 — 도메인만 바뀐 옛 주소를 새 주소로 바꿔 저장했다
            }
            if (!key.get().startsWith(purpose.keyPrefix())) {
                throw ValidationFailures.of(field, "사진은 업로드 API 가 준 주소(" + cdnPrefix + purpose.keyPrefix() + "…) 그대로만 받습니다.");
            }
            UUID id = ImageKeys.idOf(key.get()).orElseThrow(() -> notUploaded(field));
            attach.put(id, new Attach(key.get(), field));
        }
        Map<UUID, String> detach = new LinkedHashMap<>();  // id → 키
        beforeKeys.keySet().forEach(key -> {
            if (!afterKeys.contains(key)) {
                ImageKeys.idOf(key).ifPresent(id -> detach.put(id, key));
            }
        });
        if (attach.isEmpty() && detach.isEmpty()) {
            return;
        }
        Set<UUID> ids = new LinkedHashSet<>(attach.keySet());
        ids.addAll(detach.keySet());
        Map<UUID, ImageStore.Row> rows = store.lock(ids);
        attach.forEach((id, target) -> {
            ImageStore.Row row = rows.get(id);
            // 키까지 같아야 한다 — id 만 맞고 접두 · 확장자가 다른 주소는 올린 사진이 아니다
            if (row == null || !row.objectKey().equals(target.key()) || row.status() != ImageStore.Status.UNUSED) {
                throw notUploaded(target.field());
            }
        });
        Set<UUID> detached = new LinkedHashSet<>();
        detach.forEach((id, key) -> {
            ImageStore.Row row = rows.get(id);
            if (row != null && row.objectKey().equals(key) && row.status() == ImageStore.Status.USED) {
                detached.add(id);
            }
        });
        var now = clock.instant();
        store.mark(attach.keySet(), ImageStore.Status.USED, now);
        store.mark(detached, ImageStore.Status.UNUSED, now);
    }

    private static RuntimeException notUploaded(String field) {
        return ValidationFailures.of(field, "올린 사진이 아니거나 이미 다른 곳에 쓴 사진입니다. 다시 올려 주세요.");
    }

    private record Attach(String key, String field) {
    }
}

package com.grandis.nova.catalog.banner;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface BannerRepository extends JpaRepository<Banner, UUID> {

    /** 보이는 순서 — 순서 칸, 같으면 id. */
    List<Banner> findAllByOrderByPositionAscIdAsc();

    /**
     * 순서를 바꾸거나 새 배너를 끝에 붙일 때 — 모든 배너를 잠그고 읽어, 있는 배너 위에서 겹친 순서 변경 · 만들기를 줄 세운다. 배너가 하나도 없으면
     * 잠글 행이 없어 겹친 첫 만들기 둘은 둘 다 0 번을 받는다 — 순서는 (번호, id) 로 정해져 깨지지 않으니 감수한다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT b FROM Banner b ORDER BY b.position ASC, b.id ASC")
    List<Banner> lockAllInOrder();

    /** 배너 하나를 잠그고 읽는다 — 고치기 · 지우기가 겹친 순서 변경의 번호와 사진 상태를 옛 값으로 덮지 않게(사진보다 먼저 잡는다). */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT b FROM Banner b WHERE b.id = :id")
    Optional<Banner> findForUpdate(@Param("id") UUID id);
}

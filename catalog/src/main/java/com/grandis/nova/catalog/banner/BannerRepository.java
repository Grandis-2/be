package com.grandis.nova.catalog.banner;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.UUID;

public interface BannerRepository extends JpaRepository<Banner, UUID> {

    /** 보이는 순서 — 순서 칸, 같으면 id. */
    List<Banner> findAllByOrderByPositionAscIdAsc();

    /** 순서를 바꾸거나 새 배너를 끝에 붙일 때 — 모든 배너를 잠그고 읽어, 겹친 순서 변경 · 만들기가 서로의 순서를 덮지 않게 한다. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT b FROM Banner b ORDER BY b.position ASC, b.id ASC")
    List<Banner> lockAllInOrder();
}

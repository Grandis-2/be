package com.grandis.nova.catalog.promotion;

import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PromotionRepository extends JpaRepository<Promotion, UUID> {

    Optional<Promotion> findByIdempotencyKey(String idempotencyKey);

    /** 홍보 행을 잠그고 읽는다 — 같은 홍보의 수정(내용 · 묶음 교체)을 줄 세운다. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Promotion p where p.id = :id")
    Optional<Promotion> findForUpdate(@Param("id") UUID id);

    /** 관리자 목록 — 최신순. */
    @Query("select p from Promotion p order by p.createdAt desc, p.id desc")
    Page<Promotion> findNewestFirst(Pageable pageable);

    /** 그 날(한국 날짜)이 홍보 기간 안인 홍보 — 고객 사전예약 페이지. 시작이 늦은 것부터. */
    @Query("select p from Promotion p where p.startDate <= :today and p.endDate >= :today order by p.startDate desc, p.createdAt desc, p.id desc")
    List<Promotion> findShownOn(@Param("today") LocalDate today);
}

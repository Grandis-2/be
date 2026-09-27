package com.grandis.nova.probe.ddl;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** DdlValidateProbeTest 전용. member 의 엔티티 스캔 범위(com.grandis.nova.member) 밖에 둔다 — 진짜 앱이 이걸 주우면 안 된다. */
@Entity
@Table(name = "customers")
public class BrokenCustomer {

    @Id
    private Long id;

    @Column(name = "kakao_id")
    private String kakaoId;

    @Column(name = "column_that_does_not_exist")
    private String nope;
}

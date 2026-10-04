package com.grandis.nova.common.outbox.support;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** 아웃박스와 한 트랜잭션에 묶이는 업무 변경. */
@Entity
@Table(name = "it_business_records")
public class BusinessRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String name;

    protected BusinessRecord() {
    }

    public BusinessRecord(String name) {
        this.name = name;
    }

    public Long getId() {
        return id;
    }
}

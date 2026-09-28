package com.grandis.nova.member.auth.application;

import java.util.Optional;

/** 마지막으로 확인한 관리자 자격증명의 지문(Redis). 값이 바뀌었으면 자격증명이 교체된 것이다. */
public interface AdminCredentialFingerprintStore {

    Optional<String> find();

    void save(String fingerprint);
}

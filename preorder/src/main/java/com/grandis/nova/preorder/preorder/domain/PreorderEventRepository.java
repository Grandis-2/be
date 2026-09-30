package com.grandis.nova.preorder.preorder.domain;

import com.grandis.nova.preorder.preorder.PreorderStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface PreorderEventRepository extends JpaRepository<PreorderEvent, PreorderEvent.Key> {

    /** 이력은 시각이 아니라 번호 순이다. */
    List<PreorderEvent> findByPreorderIdOrderByEventSequence(Long preorderId);

    /** 그 상태로 들어간 가장 최근 이력. 취소를 누가 시작했는지 볼 때 쓴다. */
    Optional<PreorderEvent> findFirstByPreorderIdAndToStatusOrderByEventSequenceDesc(Long preorderId,
                                                                                    PreorderStatus toStatus);
}

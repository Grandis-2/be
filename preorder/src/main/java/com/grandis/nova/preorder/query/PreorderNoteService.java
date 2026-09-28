package com.grandis.nova.preorder.query;

import com.grandis.nova.preorder.preorder.PreorderRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 관리자 내부 메모. 예약에서 이력 없이 바꿀 수 있는 유일한 칸이다(ERD internal_note).
 * 상품 · 옵션 · 회원 · 순번 · 차수 · 외부 키는 접수 뒤 바뀌지 않는다.
 */
@Service
class PreorderNoteService {

    private final PreorderRepository preorders;

    public PreorderNoteService(PreorderRepository preorders) {
        this.preorders = preorders;
    }

    @Transactional
    public void changeInternalNote(String preorderToken, String internalNote) {
        preorders.getByToken(preorderToken).changeInternalNote(internalNote);
    }
}

package com.grandis.nova.preorder.preorder;

/**
 * 사건을 적용한 결과.
 *
 * @param applied 상태가 바뀌었으면 true. false 면 지금 상태에서 의미 없는 사건이라 아무것도 바꾸지 않았다
 * @param status  적용 후(바뀌지 않았으면 지금) 상태
 */
public record PreorderTransition(boolean applied, PreorderStatus status) {
}

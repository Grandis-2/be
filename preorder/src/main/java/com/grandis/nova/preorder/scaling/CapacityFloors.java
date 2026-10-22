package com.grandis.nova.preorder.scaling;

/** ECS 서비스의 최소 대수를 바꾸는 곳. 같은 값을 다시 넣어도 결과가 같아야 한다(여러 태스크가 함께 부른다). */
interface CapacityFloors {

    /** 지금 최소 대수가 min 과 다를 때만 바꾼다. 바꿨으면 true. */
    boolean ensure(String service, int min);
}

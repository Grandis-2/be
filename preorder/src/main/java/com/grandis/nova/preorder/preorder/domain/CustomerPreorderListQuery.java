package com.grandis.nova.preorder.preorder.domain;

import jakarta.persistence.EntityManager;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 회원 목록(최신순)을 조건에 맞춰 limit 건 읽는다. 저장소의 findBy(Specification) 대신 쓴다 — 그것은 저장소가 읽기 전용 트랜잭션을
 * 열어 준비 · 정리 문장이 붙고, 이것은 트랜잭션 밖에서 문장 하나다. 총계는 세지 않는다.
 */
@Component
public class CustomerPreorderListQuery {

    private final EntityManager entityManager;

    CustomerPreorderListQuery(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    /** created_at · id 내림차순. 커서 조건({@link PreorderSpecifications#after})과 같은 정렬이다. */
    public List<Preorder> findNewestFirst(Specification<Preorder> specification, int limit) {
        CriteriaBuilder builder = entityManager.getCriteriaBuilder();
        CriteriaQuery<Preorder> query = builder.createQuery(Preorder.class);
        Root<Preorder> root = query.from(Preorder.class);
        Predicate predicate = specification.toPredicate(root, query, builder);
        if (predicate != null) {
            query.where(predicate);
        }
        query.orderBy(builder.desc(root.get("createdAt")), builder.desc(root.get("id")));
        return entityManager.createQuery(query).setMaxResults(limit).getResultList();
    }
}

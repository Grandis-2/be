package com.grandis.nova.catalog.image;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface ProductImageRepository extends JpaRepository<ProductImage, Long> {

    List<ProductImage> findByProductIdOrderByKindAscBundleKeyAscPositionAsc(Long productId);

    /**
     * 색상 값 이름이 바뀌면 그 색상의 사진 묶음 키(색상 정규화값)도 따라간다. 안 옮기면 사진이 어느 색상에도 안 붙는다.
     * 벌크 UPDATE 라 영속성 컨텍스트를 거치지 않는다 — 같은 트랜잭션에서 이 호출 전에 사진을 읽어 둔 엔티티는 옛 키로 남는다(뒤에서 새로 읽으면 새 키).
     */
    @Modifying(flushAutomatically = true, clearAutomatically = false)
    @Query("update ProductImage i set i.bundleKey = :to where i.productId = :productId and i.kind = com.grandis.nova.catalog.image.ImageKind.GALLERY and i.bundleKey = :from")
    int renameGalleryBundle(@Param("productId") Long productId, @Param("from") String from, @Param("to") String to);
}

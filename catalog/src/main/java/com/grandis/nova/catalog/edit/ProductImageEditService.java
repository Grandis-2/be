package com.grandis.nova.catalog.edit;

import com.grandis.nova.catalog.detail.AdminProductDetail;
import com.grandis.nova.catalog.detail.ProductDetailService;
import com.grandis.nova.catalog.image.ImageAttachments;
import com.grandis.nova.catalog.image.ImagePurpose;
import com.grandis.nova.catalog.option.CollationDuplicates;
import com.grandis.nova.catalog.option.OptionText;
import com.grandis.nova.catalog.option.ProductOptions;
import com.grandis.nova.catalog.option.ProductOptions.Image;
import com.grandis.nova.catalog.option.ProductOptions.Section;
import com.grandis.nova.catalog.product.Product;
import com.grandis.nova.catalog.product.ProductRepository;
import com.grandis.nova.catalog.registration.ProductRegistrationValidator;
import com.grandis.nova.catalog.web.ValidationFailures;
import com.grandis.nova.common.BusinessException;
import com.grandis.nova.common.CommonErrorCode;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.UnaryOperator;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 관리자 사진 수정 — 한 장씩 넣기 · 바꾸기 · 빼기. 사진은 주소로 가리킨다(한 상품 안에서 주소는 한 번 — 등록 · 넣기 · 바꾸기가 막는다).
 * 이 규칙 전에 등록한 상품에는 같은 주소가 두 자리에 있을 수 있다 — 그런 주소를 바꾸거나 빼면 두 자리 모두 바뀐다(같은 파일이라 결과는 같다).
 * 사진은 옵션 문서(products.options) 안에 있어 문서를 고쳐 다시 쓰고, 썸네일도 같이 다시 계산한다({@link Product#replaceOptions}).
 *
 * <p><b>사전예약 오픈 뒤에도 고칠 수 있다</b>(2026-10-13 결정) — 사진은 접수된 예약 · 주문이 복사해 두는 값이 아니고 preorder 의 접수 판정에도
 * 쓰이지 않는다. 다른 수정처럼 상품 행을 잠그고 읽는다 — 문서를 통째로 다시 쓰므로 잠그지 않으면 그사이 커밋된 수정을 덮는다.
 * 이미 만든 럭키 드로우 회차 · 장바구니 표시는 order 가 받아 둔 사진을 쓰므로 바뀌지 않거나(회차 스냅샷) 다음 조회부터 바뀐다(장바구니).
 */
@Service
public class ProductImageEditService {

    private final ProductRepository products;
    private final ProductDetailService detailService;
    private final CollationDuplicates duplicates;
    private final ImageAttachments images;

    public ProductImageEditService(ProductRepository products, ProductDetailService detailService, CollationDuplicates duplicates,
                                   ImageAttachments images) {
        this.products = products;
        this.detailService = detailService;
        this.duplicates = duplicates;
        this.images = images;
    }

    /** 그 묶음 맨 뒤에 넣는다. 빈 상품 사진 묶음에 넣은 첫 장은 대표가 된다(등록과 같은 규칙). 상품 사진 묶음은 최대 10장. */
    @Transactional
    public AdminProductDetail add(UUID productId, ProductImageAddRequest request) {
        Product product = lock(productId);
        ProductOptions document = product.getOptions();
        requireAbsent(document, request.url(), "url");
        boolean hasBundle = request.bundle() != null && !request.bundle().isBlank();
        ProductOptions next = request.kind() == ProductImageAddRequest.Kind.GALLERY
                ? addToGallery(document, hasBundle ? request.bundle() : null, request.url())
                : addToDetail(document, hasBundle ? request.bundle() : null, request.url());
        return save(product, next, "url");
    }

    /** 자리와 대표 여부는 그대로 두고 주소만 바꾼다. */
    @Transactional
    public AdminProductDetail replace(UUID productId, ProductImageReplaceRequest request) {
        Product product = lock(productId);
        ProductOptions document = product.getOptions();
        requirePresent(document, request.url());
        if (!request.newUrl().equals(request.url())) {
            requireAbsent(document, request.newUrl(), "newUrl");
        }
        return save(product, map(document, list -> list.stream()
                .map(image -> image.url().equals(request.url()) ? new Image(request.newUrl(), image.primary()) : image)
                .toList()), "newUrl");
    }

    /**
     * 뺀다. 상품 사진 묶음에서 대표를 빼면 남은 첫 장이 대표가 된다. 마지막 한 장도 뺄 수 있다(사진 없는 색상 · 상품이 된다 — 등록도 사진 없이 받는다).
     * 상세 영역은 대표를 자동으로 정하지 않고(등록과 같다), 비면 영역째 없앤다(등록은 빈 영역을 받지 않는다).
     */
    @Transactional
    public AdminProductDetail remove(UUID productId, String url) {
        if (url == null || url.isBlank()) {
            throw ValidationFailures.of("url", "뺄 사진 주소가 필요합니다.");
        }
        Product product = lock(productId);
        ProductOptions document = product.getOptions();
        requirePresent(document, url);
        UnaryOperator<List<Image>> withoutGallery = images -> withDefaultPrimary(without(images, url), images);
        List<ProductOptions.Axis> axes = document.axes().stream()
                .map(axis -> new ProductOptions.Axis(axis.key(), axis.label(), axis.values().stream()
                        .map(value -> withImages(value, withoutGallery.apply(value.images())))
                        .toList()))
                .toList();
        List<Section> detail = document.detailImages().stream()
                .map(section -> new Section(section.section(), without(section.images(), url)))
                .filter(section -> !section.images().isEmpty())
                .toList();
        return save(product, new ProductOptions(axes, withoutGallery.apply(document.defaultImages()), detail, document.warranty()), "url");
    }

    private ProductOptions addToGallery(ProductOptions document, String bundle, String url) {
        var color = document.axis(OptionText.COLOR);
        if (color.isEmpty()) {
            if (bundle != null) {
                throw ValidationFailures.of("bundle", "color 축이 없는 상품은 기본 묶음만 받습니다(bundle 을 비웁니다).");
            }
            return new ProductOptions(document.axes(), appended(document.defaultImages(), url), document.detailImages(), document.warranty());
        }
        if (bundle == null) {
            throw ValidationFailures.of("bundle", "color 축이 있는 상품은 색상을 정해야 합니다.");
        }
        String normalized = OptionText.normalizeFor(OptionText.COLOR, bundle);
        ProductOptions.Value target = color.get().valueByNormalized(normalized)
                .orElseThrow(() -> ValidationFailures.of("bundle", "color 축에 없는 값입니다: " + bundle));
        List<ProductOptions.Axis> axes = document.axes().stream()
                .map(axis -> !OptionText.COLOR.equals(axis.key()) ? axis : new ProductOptions.Axis(axis.key(), axis.label(), axis.values().stream()
                        .map(value -> value.id().equals(target.id()) ? withImages(value, appended(value.images(), url)) : value)
                        .toList()))
                .toList();
        return new ProductOptions(axes, document.defaultImages(), document.detailImages(), document.warranty());
    }

    /** 같은 영역은 등록의 중복 판정과 같은 규칙 — 앱 근사 키로 먼저, 근사가 못 펴는 확장 문자(ß=ss)는 DB 콜레이션으로({@link CollationDuplicates}). */
    private ProductOptions addToDetail(ProductOptions document, String bundle, String url) {
        if (bundle == null) {
            throw ValidationFailures.of("bundle", "상세 영역 이름이 필요합니다.");
        }
        String section = ProductRegistrationValidator.requireStorableText(OptionText.normalize(bundle),
                ProductRegistrationValidator.MAX_OPTION_VALUE_LENGTH, "bundle");
        String key = ProductRegistrationValidator.collationKey(section);
        List<Section> detail = new ArrayList<>();
        boolean added = false;
        for (Section existing : document.detailImages()) {
            if (!added && sameSection(existing.section(), section, key)) {
                List<Image> images = new ArrayList<>(existing.images());
                images.add(new Image(url, false));
                detail.add(new Section(existing.section(), images));
                added = true;
            } else {
                detail.add(existing);
            }
        }
        if (!added) {
            detail.add(new Section(section, List.of(new Image(url, false))));
        }
        return new ProductOptions(document.axes(), document.defaultImages(), detail, document.warranty());
    }

    private boolean sameSection(String existing, String section, String key) {
        return ProductRegistrationValidator.collationKey(existing).equals(key) || duplicates.any(List.of(existing, section));
    }

    private static List<Image> appended(List<Image> images, String url) {
        if (images.size() >= ProductRegistrationValidator.MAX_GALLERY_IMAGES_PER_BUNDLE) {
            throw ValidationFailures.of("url", "묶음당 최대 %d장입니다.".formatted(ProductRegistrationValidator.MAX_GALLERY_IMAGES_PER_BUNDLE));
        }
        List<Image> next = new ArrayList<>(images);
        next.add(new Image(url, images.isEmpty()));
        return next;
    }

    private static List<Image> without(List<Image> images, String url) {
        return images.stream().filter(image -> !image.url().equals(url)).toList();
    }

    /** 대표를 뺐으면 남은 첫 장을 대표로. 원래 대표가 없던 묶음(옮겨 온 문서)은 그대로 둔다. */
    private static List<Image> withDefaultPrimary(List<Image> remaining, List<Image> before) {
        boolean hadPrimary = before.stream().anyMatch(Image::primary);
        if (remaining.isEmpty() || !hadPrimary || remaining.stream().anyMatch(Image::primary)) {
            return remaining;
        }
        List<Image> next = new ArrayList<>(remaining);
        next.set(0, new Image(remaining.getFirst().url(), true));
        return next;
    }

    /** 문서의 사진 목록 전부(기본 묶음 · 색상 값 · 상세 영역)에 같은 변환을 건다. 색상 아닌 축의 값은 사진이 없어 그대로다. */
    private static ProductOptions map(ProductOptions document, UnaryOperator<List<Image>> change) {
        List<ProductOptions.Axis> axes = document.axes().stream()
                .map(axis -> new ProductOptions.Axis(axis.key(), axis.label(), axis.values().stream()
                        .map(value -> withImages(value, change.apply(value.images())))
                        .toList()))
                .toList();
        List<Section> detail = document.detailImages().stream()
                .map(section -> new Section(section.section(), change.apply(section.images())))
                .toList();
        return new ProductOptions(axes, change.apply(document.defaultImages()), detail, document.warranty());
    }

    private static ProductOptions.Value withImages(ProductOptions.Value value, List<Image> images) {
        return new ProductOptions.Value(value.id(), value.value(), value.normalized(), value.hex(), value.surcharge(), images);
    }

    private static boolean contains(ProductOptions document, String url) {
        List<List<Image>> all = new ArrayList<>();
        all.add(document.defaultImages());
        document.axes().forEach(axis -> axis.values().forEach(value -> all.add(value.images())));
        document.detailImages().forEach(section -> all.add(section.images()));
        return all.stream().flatMap(List::stream).anyMatch(image -> image.url().equals(url));
    }

    private static void requirePresent(ProductOptions document, String url) {
        if (!contains(document, url)) {
            throw ValidationFailures.of("url", "이 상품에 없는 사진입니다.");
        }
    }

    private static void requireAbsent(ProductOptions document, String url, String field) {
        if (contains(document, url)) {
            throw ValidationFailures.of(field, "이 상품에 이미 있는 사진입니다.");
        }
    }

    private Product lock(UUID productId) {
        return products.findForUpdate(productId).orElseThrow(() -> new BusinessException(CommonErrorCode.NOT_FOUND));
    }

    /** 문서를 바꾸고 사진을 붙이고 뗀다. field 는 새 사진 주소가 올린 사진이 아닐 때 400 이 가리키는 칸. */
    private AdminProductDetail save(Product product, ProductOptions next, String field) {
        images.sync(ImagePurpose.PRODUCT, product.getOptions().imageUrls(), next.imageUrls(), url -> field);
        product.replaceOptions(next);
        products.flush();
        return detailService.findAdminProduct(product.getId());
    }
}

package com.grandis.nova.catalog.api;

import com.grandis.nova.catalog.image.ImagePurpose;
import com.grandis.nova.catalog.image.ImageUploadService;
import com.grandis.nova.catalog.image.UploadBusyException;
import com.grandis.nova.catalog.web.ValidationFailures;
import com.grandis.nova.common.CommonErrorCode;
import com.grandis.nova.common.web.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.multipart.MultipartHttpServletRequest;

import java.util.List;

/**
 * 관리자 사진 업로드 — 한 요청에 한 장. 서버가 검사 · 다시 인코딩해 S3 에 올리고 CDN 주소를 준다({@link ImageUploadService}).
 * 받은 주소는 상품 · 홍보 저장에 넣는다 — 저장이 그 사진을 붙인다. 붙이지 않은 사진은 정리 배치가 기준 시간 뒤 지운다.
 *
 * 멀티파트 상한(파일 10MB · 요청 11MB · 파트 수)을 넘으면 컨트롤러 전에 413 이다(CatalogDefaults) — 오류 코드는 VALIDATION_FAILED 라
 * 프론트는 상태 코드 413 으로 "파일이 너무 큽니다" 를 가른다.
 */
@Tag(name = "관리자 사진")
@RestController
@RequestMapping("/api/v1/admin/images")
public class AdminImageController {

    static final String FILE_PART = "file";

    private final ImageUploadService uploads;

    public AdminImageController(ImageUploadService uploads) {
        this.uploads = uploads;
    }

    @Operation(summary = "사진 올리기", description = "multipart/form-data — 파일 파트 file 하나 · purpose(PRODUCT 상품 · PROMOTION 홍보 · BANNER 메인 배너). "
            + "201 imageUrl. JPEG · PNG(RGB · 회색조)만, 1 ~ 10MiB, 가로 · 세로 8,000 픽셀 이하 · 전체 2,500만 픽셀 이하 — 어긋나면 400(file). "
            + "파일 · 요청이 상한을 넘으면 413. 동시에 올리는 사진이 많으면 503 + Retry-After(초), 사진 저장소가 실패하면 503 — 같은 파일을 다시 올린다. "
            + "EXIF 등 메타데이터는 지우고 JPEG 의 방향은 픽셀에 반영한다")
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ApiResponse<ImageUploadResponse>> upload(@RequestParam ImagePurpose purpose,
                                                                   // 문서에 파일 파트를 싣는 자리 — 검사는 아래 onlyFile 이 전체 파트로 한다
                                                                   @RequestPart(name = FILE_PART, required = false) MultipartFile file,
                                                                   MultipartHttpServletRequest request) {
        String imageUrl = uploads.upload(purpose, onlyFile(request));
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(new ImageUploadResponse(imageUrl)));
    }

    /**
     * 파일 파트가 전체에서 정확히 하나이고 이름이 file 이어야 한다. Tomcat 은 같은 이름 파트 둘을 막지 않고(@RequestParam 은 첫 파트를 준다)
     * 다른 이름의 파일 파트도 끼울 수 있어, 이름별이 아니라 전체 파일 수를 센다.
     */
    private static MultipartFile onlyFile(MultipartHttpServletRequest request) {
        MultiValueMap<String, MultipartFile> files = request.getMultiFileMap();
        int count = files.values().stream().mapToInt(List::size).sum();
        List<MultipartFile> named = files.get(FILE_PART);
        if (count != 1 || named == null) {
            throw ValidationFailures.of(FILE_PART, "파일 파트 file 하나만 보내야 합니다.");
        }
        return named.getFirst();
    }

    @ExceptionHandler(UploadBusyException.class)
    ResponseEntity<ApiResponse<Void>> busy(UploadBusyException e) {
        CommonErrorCode code = CommonErrorCode.DEPENDENCY_UNAVAILABLE;
        return ResponseEntity.status(code.status()).header(HttpHeaders.RETRY_AFTER, Integer.toString(e.retryAfterSeconds()))
                .body(ApiResponse.fail(code, "올리는 사진이 많습니다. 잠시 후 다시 올려 주세요."));
    }

    /** 올린 사진의 CDN 주소 — 상품 · 홍보 저장의 사진 주소에 넣는다. */
    public record ImageUploadResponse(String imageUrl) {
    }
}

package com.grandis.nova.catalog.image;

import com.grandis.nova.catalog.web.ValidationFailures;
import com.grandis.nova.common.BusinessException;
import org.springframework.core.io.InputStreamSource;
import org.springframework.stereotype.Component;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageInputStream;
import java.awt.color.ColorSpace;
import java.awt.image.BufferedImage;
import java.awt.image.WritableRaster;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Iterator;
import java.util.List;

/**
 * 올린 파일을 검사하고 다시 인코딩한다(OWASP 파일 업로드: 앞 바이트로 형식 판정 · 풀기 전 크기 확인 · 디코딩 후 재인코딩 · 메타데이터 제거).
 * 다시 인코딩한 파일에는 픽셀과 색 정보만 남는다 — EXIF(촬영 위치 등) · 주석 · 뒤에 붙은 바이트는 빠지고, 색 정보도 검사해 새로 쓴다({@link PngColorChunks}).
 *
 * 메모리: 풀린 크기는 파일 크기와 상관없다(작은 PNG 가 수백 MB 로 풀린다). 그래서 풀기 전에 헤더의 가로 · 세로로 {@link #MAX_SIDE} · {@link #MAX_PIXELS}
 * 를 본다. 한 장이 쓰는 메모리의 상한과 동시 처리 수({@link ImageUploadService})가 함께 힙 사용을 묶는다.
 */
@Component
public class ImageProcessor {

    /** 10MiB(10,485,760 바이트) — 원본 · 다시 인코딩한 파일 모두. */
    public static final long MAX_BYTES = 10L * 1024 * 1024;
    /** 한 변. */
    static final int MAX_SIDE = 8_000;
    /** 가로 × 세로. */
    static final long MAX_PIXELS = 25_000_000L;
    /** JPEG 다시 인코딩 품질. */
    static final float JPEG_QUALITY = 0.9f;

    /** 검사를 통과한 원본의 형식과 헤더의 가로 · 세로. */
    public record Inspected(ImageFormat format, int width, int height) {
    }

    /**
     * 형식 · 크기 · 가로 · 세로를 본다. 픽셀은 풀지 않고 파일을 힙에 다 올리지도 않는다(앞 바이트와 헤더만 스트림으로 읽는다) — 동시 처리 허가를
     * 받기 전이라, 여기서 파일을 통째로 읽으면 허가 밖의 요청 수만큼 힙을 쓴다. 어긋나면 400(file).
     */
    public Inspected inspect(long size, InputStreamSource file) {
        if (size < 1 || size > MAX_BYTES) {
            throw invalid("1 ~ %d 바이트(10MiB)여야 합니다.".formatted(MAX_BYTES));
        }
        ImageFormat format;
        try (InputStream head = file.getInputStream()) {
            format = ImageFormat.sniff(head.readNBytes(ImageFormat.SIGNATURE_LENGTH)).orElseThrow(() -> invalid("JPEG · PNG 만 올릴 수 있습니다."));
        } catch (IOException e) {
            throw unreadable();
        }
        int width;
        int height;
        try (InputStream raw = file.getInputStream(); ImageInputStream in = ImageIO.createImageInputStream(raw)) {
            ImageReader reader = reader(format);
            try {
                // 메타데이터를 읽지 않는다 — PNG 의 압축 청크(zTXt · iCCP)를 풀지 않는다
                reader.setInput(in, true, true);
                width = reader.getWidth(0);
                height = reader.getHeight(0);
            } finally {
                reader.dispose();
            }
        } catch (IOException | RuntimeException e) {
            throw unreadable();
        }
        if (width < 1 || height < 1 || width > MAX_SIDE || height > MAX_SIDE || (long) width * height > MAX_PIXELS) {
            throw invalid("가로 · 세로는 %d 픽셀 이하, 전체 %d 픽셀 이하여야 합니다.".formatted(MAX_SIDE, MAX_PIXELS));
        }
        return new Inspected(format, width, height);
    }

    /**
     * 검사한 그 파일의 바이트를 풀고(JPEG 는 방향을 픽셀에 반영) 같은 형식으로 다시 인코딩한다. 색 공간이 RGB · 회색조가 아니거나(CMYK 등)
     * 결과가 상한을 넘으면 400(file).
     */
    public byte[] reencode(Inspected image, byte[] bytes) {
        if (ImageFormat.sniff(bytes).orElse(null) != image.format()) {
            throw unreadable();
        }
        BufferedImage pixels = decode(image.format(), bytes);
        if (pixels.getWidth() != image.width() || pixels.getHeight() != image.height()) {
            throw unreadable();
        }
        int type = pixels.getColorModel().getColorSpace().getType();
        if (type != ColorSpace.TYPE_RGB && type != ColorSpace.TYPE_GRAY) {
            throw invalid("RGB · 회색조 사진만 올릴 수 있습니다. CMYK 는 RGB 로 다시 저장해 주세요.");
        }
        byte[] out;
        if (image.format() == ImageFormat.JPEG) {
            out = writeJpeg(orient(pixels, JpegOrientation.of(bytes)));
        } else {
            List<byte[]> color;
            try {
                color = PngColorChunks.extract(bytes);
            } catch (IllegalArgumentException e) {
                throw invalid("색 프로파일(ICC)을 읽을 수 없습니다. sRGB 로 다시 저장해 올려 주세요.");
            }
            out = PngColorChunks.insertAfterHeader(writePng(pixels), color);
        }
        if (out.length > MAX_BYTES) {  // PNG 는 색 청크를 더한 뒤 한 번 더 본다
            throw tooLarge();
        }
        return out;
    }

    private static BufferedImage decode(ImageFormat format, byte[] bytes) {
        try (ImageInputStream in = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            ImageReader reader = reader(format);
            try {
                reader.setInput(in, true, true);
                // 첫 장면만 — 움직이는 PNG 의 나머지 장면은 버린다
                return reader.read(0);
            } finally {
                reader.dispose();
            }
        } catch (IOException | RuntimeException e) {
            // 기본 ImageIO 는 일부 CMYK · 깨진 파일에서 IIOException 이나 런타임 예외를 던진다
            throw unreadable();
        }
    }

    /**
     * EXIF 방향(1 ~ 8)대로 돌리거나 뒤집는다. 1 이면 그대로. 90° 단위라 보간 없이 픽셀 값을 옮긴다.
     * 래스터끼리 직접 옮긴다 — Graphics2D 로 변환해 그리면 중간 이미지를 만들어(실측: 5,000×5,000 JPEG 이 힙 400MB 에서도 OutOfMemoryError)
     * 한 장의 메모리 상한이 깨진다. 이 방식은 결과 래스터 하나와 줄 버퍼만 더 쓴다.
     */
    static BufferedImage orient(BufferedImage src, int orientation) {
        if (orientation == 1) {
            return src;
        }
        if (orientation < 1 || orientation > 8) {
            throw new IllegalArgumentException("orientation " + orientation);
        }
        int w = src.getWidth();
        int h = src.getHeight();
        boolean swap = orientation >= 5;
        WritableRaster from = src.getRaster();
        WritableRaster to = src.getColorModel().createCompatibleWritableRaster(swap ? h : w, swap ? w : h);
        int bands = from.getNumBands();
        int[] row = new int[w * bands];
        int[] pixel = new int[bands];
        for (int y = 0; y < h; y++) {
            from.getPixels(0, y, w, 1, row);
            for (int x = 0; x < w; x++) {
                // 원본 점 (x, y) 가 놓일 자리 — EXIF 의 "보일 점 ← 원본 점" 정의를 거꾸로 푼 것
                int tx;
                int ty;
                switch (orientation) {
                    case 2 -> { tx = w - 1 - x; ty = y; }
                    case 3 -> { tx = w - 1 - x; ty = h - 1 - y; }
                    case 4 -> { tx = x; ty = h - 1 - y; }
                    case 5 -> { tx = y; ty = x; }
                    case 6 -> { tx = h - 1 - y; ty = x; }
                    case 7 -> { tx = h - 1 - y; ty = w - 1 - x; }
                    default -> { tx = y; ty = w - 1 - x; }  // 8
                }
                System.arraycopy(row, x * bands, pixel, 0, bands);
                to.setPixel(tx, ty, pixel);
            }
        }
        return new BufferedImage(src.getColorModel(), to, src.isAlphaPremultiplied(), null);
    }

    private static byte[] writeJpeg(BufferedImage pixels) {
        ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();
        ImageWriteParam param = writer.getDefaultWriteParam();
        param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
        param.setCompressionQuality(JPEG_QUALITY);
        return write(writer, pixels, param);
    }

    private static byte[] writePng(BufferedImage pixels) {
        ImageWriter writer = ImageIO.getImageWritersByFormatName("png").next();
        return write(writer, pixels, writer.getDefaultWriteParam());
    }

    /** 메타데이터 없이 쓴다(IIOImage 의 메타데이터 null). 결과가 상한을 넘는 순간 멈추고 400 이다. */
    private static byte[] write(ImageWriter writer, BufferedImage pixels, ImageWriteParam param) {
        try (BoundedImageOutputStream out = new BoundedImageOutputStream((int) MAX_BYTES)) {
            writer.setOutput(out);
            writer.write(null, new IIOImage(pixels, null, null), param);
            return out.toByteArray();
        } catch (BoundedImageOutputStream.TooLarge e) {
            throw tooLarge();
        } catch (IOException | RuntimeException e) {
            throw unreadable();
        } finally {
            writer.dispose();
        }
    }

    private static ImageReader reader(ImageFormat format) {
        Iterator<ImageReader> readers = ImageIO.getImageReadersByFormatName(format.imageIoName());
        return readers.next();
    }

    private static BusinessException tooLarge() {
        return invalid("다시 저장한 파일이 %d 바이트(10MiB)를 넘습니다. 크기를 줄여 올려 주세요.".formatted(MAX_BYTES));
    }

    private static BusinessException unreadable() {
        return invalid("사진을 읽을 수 없습니다. JPEG · PNG 파일인지 확인해 주세요.");
    }

    private static BusinessException invalid(String message) {
        return ValidationFailures.of("file", message);
    }
}

package com.grandis.nova.catalog.image;

import com.grandis.nova.common.BusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.core.io.ByteArrayResource;

import javax.imageio.ImageIO;
import java.awt.color.ColorSpace;
import java.awt.color.ICC_Profile;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.zip.InflaterInputStream;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("사진 검사 · 다시 인코딩 — 형식은 앞 바이트로, 크기는 풀기 전에, 방향은 픽셀로, 색 정보만 남긴다")
class ImageProcessorTest {

    private final ImageProcessor processor = new ImageProcessor();

    @Test
    @DisplayName("대조군 — JPEG · PNG 는 통과하고 같은 형식 · 같은 크기로 다시 저장된다")
    void acceptsJpegAndPng() throws IOException {
        for (byte[] original : List.of(ImageFixtures.jpeg(40, 30), ImageFixtures.png(40, 30))) {
            ImageProcessor.Inspected inspected = inspect(original);
            byte[] out = processor.reencode(inspected, original);
            assertThat(ImageFormat.sniff(out)).contains(inspected.format());
            BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(out));
            assertThat(decoded.getWidth()).isEqualTo(40);
            assertThat(decoded.getHeight()).isEqualTo(30);
        }
    }

    @Test
    @DisplayName("여러 픽셀 형식이 같은 크기로 다시 저장된다 — 팔레트 · 16비트 회색 · 알파 PNG(투명도 유지) · 회색 JPEG · progressive JPEG")
    void roundTripsPixelFormats() throws Exception {
        BufferedImage alpha = new BufferedImage(6, 4, BufferedImage.TYPE_INT_ARGB);
        alpha.setRGB(1, 1, 0x40FF0000);
        List<byte[]> inputs = List.of(
                ImageFixtures.encode(new BufferedImage(6, 4, BufferedImage.TYPE_BYTE_INDEXED), "png"),
                ImageFixtures.encode(new BufferedImage(6, 4, BufferedImage.TYPE_USHORT_GRAY), "png"),
                ImageFixtures.encode(alpha, "png"),
                ImageFixtures.encode(new BufferedImage(6, 4, BufferedImage.TYPE_BYTE_GRAY), "jpg"),
                progressiveJpeg(ImageFixtures.rgb(6, 4)));
        for (byte[] input : inputs) {
            BufferedImage out = ImageIO.read(new ByteArrayInputStream(process(input)));
            assertThat(out.getWidth()).isEqualTo(6);
            assertThat(out.getHeight()).isEqualTo(4);
        }
        BufferedImage alphaOut = ImageIO.read(new ByteArrayInputStream(process(ImageFixtures.encode(alpha, "png"))));
        assertThat(alphaOut.getRGB(1, 1) >>> 24).as("투명도").isEqualTo(0x40);
    }

    private static byte[] progressiveJpeg(BufferedImage image) throws IOException {
        javax.imageio.ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();
        javax.imageio.ImageWriteParam param = writer.getDefaultWriteParam();
        param.setProgressiveMode(javax.imageio.ImageWriteParam.MODE_DEFAULT);
        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
        try (javax.imageio.stream.MemoryCacheImageOutputStream out = new javax.imageio.stream.MemoryCacheImageOutputStream(bytes)) {
            writer.setOutput(out);
            writer.write(null, new javax.imageio.IIOImage(image, null, null), param);
        } finally {
            writer.dispose();
        }
        return bytes.toByteArray();
    }

    @Test
    @DisplayName("형식은 앞 바이트로만 — GIF · 글자 · 빈 파일은 400(file)")
    void rejectsOtherFormats() {
        assertRejected(ImageFixtures.encode(ImageFixtures.rgb(4, 4), "gif"));
        assertRejected("not an image".getBytes());
        assertRejected(new byte[0]);
        assertRejected(new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 0, 1, 2});  // 시그니처만 JPEG — 읽지 못한다
    }

    @Test
    @DisplayName("10MiB 를 넘으면 앞 바이트가 맞아도 400")
    void rejectsOverMaxBytes() {
        byte[] big = Arrays.copyOf(ImageFixtures.jpeg(4, 4), (int) ImageProcessor.MAX_BYTES + 1);
        assertRejected(big);
        byte[] limit = Arrays.copyOf(ImageFixtures.jpeg(4, 4), (int) ImageProcessor.MAX_BYTES);
        assertThat(inspect(limit).format()).isEqualTo(ImageFormat.JPEG);
    }

    @Test
    @DisplayName("가로 · 세로는 풀기 전 헤더로 본다 — 헤더가 8,001 이거나 픽셀 상한을 넘으면 픽셀 데이터를 보지 않고 400, 상한 안이면 헤더 크기 그대로 통과")
    void checksSizeFromHeaderBeforeDecoding() {
        // 거절은 inspect 에서, 크기 문구로 — 픽셀 데이터가 가짜라 풀었다면 "읽을 수 없습니다" 로 갈렸을 것이다
        assertRejectedForSize(ImageFixtures.pngClaiming(8_001, 1));
        assertRejectedForSize(ImageFixtures.pngClaiming(1, 8_001));
        assertRejectedForSize(ImageFixtures.pngClaiming(5_001, 5_000));  // 25,005,000 > 25,000,000
        ImageProcessor.Inspected atLimit = inspect(ImageFixtures.pngClaiming(5_000, 5_000));
        assertThat(atLimit.width()).isEqualTo(5_000);
        assertThat(atLimit.height()).isEqualTo(5_000);
        ImageProcessor.Inspected side = inspect(ImageFixtures.pngClaiming(8_000, 1));
        assertThat(side.width()).isEqualTo(8_000);
    }

    @Test
    @DisplayName("CMYK JPEG 는 400 — RGB 로 다시 저장해 올린다")
    void rejectsCmyk() {
        byte[] cmyk = ImageFixtures.cmykJpeg(16, 16);
        ImageProcessor.Inspected inspected = inspect(cmyk);
        assertThatThrownBy(() -> processor.reencode(inspected, cmyk))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.details().toString()).contains("file", "CMYK"));
    }

    @Test
    @DisplayName("다시 저장한 JPEG 에는 EXIF(APP1)가 없다")
    void stripsExif() {
        byte[] withExif = ImageFixtures.withExifOrientation(ImageFixtures.jpeg(20, 10), 1, true);
        assertThat(ImageFixtures.hasJpegMarker(withExif, 0xE1)).isTrue();
        byte[] out = process(withExif);
        assertThat(ImageFixtures.hasJpegMarker(out, 0xE1)).isFalse();
    }

    @Test
    @DisplayName("파일 뒤에 붙인 바이트(polyglot — 스크립트 덧붙임)는 다시 인코딩한 결과에 없다 — JPEG · PNG 둘 다")
    void appendedPayloadIsDropped() {
        byte[] payload = "<script>alert(1)</script>".getBytes();
        for (byte[] image : List.of(ImageFixtures.jpeg(20, 10), ImageFixtures.png(20, 10))) {
            byte[] polyglot = new byte[image.length + payload.length];
            System.arraycopy(image, 0, polyglot, 0, image.length);
            System.arraycopy(payload, 0, polyglot, image.length, payload.length);
            assertThat(new String(polyglot, java.nio.charset.StandardCharsets.ISO_8859_1)).as("대조군 — 원본에는 있다").contains("<script>");

            byte[] out = process(polyglot);
            assertThat(new String(out, java.nio.charset.StandardCharsets.ISO_8859_1)).doesNotContain("<script>");
        }
    }

    @ParameterizedTest(name = "Orientation={0}")
    @ValueSource(ints = {1, 2, 3, 4, 5, 6, 7, 8})
    @DisplayName("EXIF 방향대로 픽셀을 옮긴다 — 보일 그림의 (x, y) 는 EXIF 규격이 정한 원본 점이다")
    void appliesOrientation(int orientation) {
        int w = 5;
        int h = 3;
        BufferedImage src = ImageFixtures.rgb(w, h);
        BufferedImage out = ImageProcessor.orient(src, orientation);
        boolean swap = orientation >= 5;
        assertThat(out.getWidth()).isEqualTo(swap ? h : w);
        assertThat(out.getHeight()).isEqualTo(swap ? w : h);
        // EXIF 2.3 의 방향 정의: 보일 점 (x, y) 가 원본의 어느 점인지
        Map<Integer, BiFunction<Integer, Integer, int[]>> source = Map.of(
                1, (x, y) -> new int[]{x, y},
                2, (x, y) -> new int[]{w - 1 - x, y},
                3, (x, y) -> new int[]{w - 1 - x, h - 1 - y},
                4, (x, y) -> new int[]{x, h - 1 - y},
                5, (x, y) -> new int[]{y, x},
                6, (x, y) -> new int[]{y, h - 1 - x},
                7, (x, y) -> new int[]{w - 1 - y, h - 1 - x},
                8, (x, y) -> new int[]{w - 1 - y, x});
        for (int y = 0; y < out.getHeight(); y++) {
            for (int x = 0; x < out.getWidth(); x++) {
                int[] s = source.get(orientation).apply(x, y);
                assertThat(out.getRGB(x, y)).as("(%d,%d) ← (%d,%d)", x, y, s[0], s[1]).isEqualTo(src.getRGB(s[0], s[1]));
            }
        }
    }

    @Test
    @DisplayName("JPEG 의 EXIF 방향을 읽어 다시 저장한 파일에 반영한다 — 바이트 순서(MM · II) 둘 다, 방향 6 이면 가로 · 세로가 바뀐다")
    void reencodedJpegIsRotated() throws IOException {
        for (boolean bigEndian : new boolean[]{true, false}) {
            byte[] rotated = ImageFixtures.withExifOrientation(ImageFixtures.jpeg(40, 20), 6, bigEndian);
            BufferedImage out = ImageIO.read(new ByteArrayInputStream(process(rotated)));
            assertThat(out.getWidth()).isEqualTo(20);
            assertThat(out.getHeight()).isEqualTo(40);
        }
        assertThat(JpegOrientation.of(ImageFixtures.jpeg(4, 4))).isEqualTo(1);
    }

    @Test
    @DisplayName("PNG 는 원본의 색 청크(iCCP)를 그대로 옮기고, 라이터는 색 청크를 따로 쓰지 않는다 — 다른 보조 청크(tEXt)는 빠진다")
    void keepsPngColorChunksOnly() {
        byte[] plain = ImageFixtures.png(8, 8);
        byte[] withChunks = ImageFixtures.withChunkAfterHeader(
                ImageFixtures.withChunkAfterHeader(plain, ImageFixtures.chunk("tEXt", "Comment\0hello".getBytes())), ImageFixtures.iccpChunk());
        assertThat(ImageFixtures.pngChunkTypes(withChunks)).contains("iCCP", "tEXt");

        byte[] out = process(withChunks);
        assertThat(ImageFixtures.pngChunkTypes(out)).containsOnlyOnce("iCCP").doesNotContain("tEXt");
        assertThat(ImageFixtures.pngChunkTypes(out).indexOf("iCCP")).isEqualTo(1);  // IHDR 바로 뒤 — IDAT 앞

        byte[] plainOut = process(plain);
        assertThat(ImageFixtures.pngChunkTypes(plainOut)).doesNotContain("iCCP", "sRGB", "gAMA", "cHRM");
    }

    @Test
    @DisplayName("iCCP 는 풀어 읽은 프로파일로 새로 쓴다 — 같은 프로파일이 남는다. 프로파일이 아닌 내용 · 1MiB 넘게 풀리는 내용은 400(file)")
    void iccpIsParsedAndRewritten() throws Exception {
        byte[] linear = ICC_Profile.getInstance(ColorSpace.CS_LINEAR_RGB).getData();
        byte[] out = process(ImageFixtures.withChunkAfterHeader(ImageFixtures.png(8, 8), ImageFixtures.iccpChunk(linear)));
        byte[] data = ImageFixtures.pngChunkData(out, "iCCP");
        int nul = 0;
        while (data[nul] != 0) {
            nul++;
        }
        byte[] inflated = new InflaterInputStream(new ByteArrayInputStream(data, nul + 2, data.length - nul - 2)).readAllBytes();
        assertThat(ICC_Profile.getInstance(inflated).getData()).isEqualTo(ICC_Profile.getInstance(linear).getData());

        byte[] notAProfile = ImageFixtures.withChunkAfterHeader(ImageFixtures.png(8, 8), ImageFixtures.iccpChunk("<script>".repeat(100).getBytes()));
        assertThatThrownBy(() -> process(notAProfile))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.details().toString()).contains("file", "ICC"));
        byte[] bomb = ImageFixtures.withChunkAfterHeader(ImageFixtures.png(8, 8), ImageFixtures.iccpChunk(new byte[PngColorChunks.MAX_PROFILE_BYTES + 1]));
        assertThat(bomb.length).as("압축돼 작다").isLessThan(16 * 1024);
        assertThatThrownBy(() -> process(bomb))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.details().toString()).contains("file", "ICC"));
        // 거절 이유가 풀기 상한인지 — 0 바이트는 상한이 없어도 프로파일 파싱에서 거절되므로 위 단언만으로는 상한을 가리지 못한다
        assertThatThrownBy(() -> PngColorChunks.extract(bomb)).isInstanceOf(IllegalArgumentException.class).hasMessage("iCCP too large");
    }

    @Test
    @DisplayName("iCCP 와 sRGB 가 함께 오면 iCCP 만 남긴다(넓은 색역을 잃지 않는다) · 길이가 틀린 gAMA 는 버린다 · sRGB 만이면 sRGB 를 남긴다")
    void srgbWinsAndMalformedFixedChunksAreDropped() {
        byte[] png = ImageFixtures.withChunkAfterHeader(ImageFixtures.png(8, 8), ImageFixtures.iccpChunk());
        png = ImageFixtures.withChunkAfterHeader(png, ImageFixtures.chunk("sRGB", new byte[]{0}));
        png = ImageFixtures.withChunkAfterHeader(png, ImageFixtures.chunk("gAMA", new byte[]{0, 0, 1}));
        assertThat(ImageFixtures.pngChunkTypes(png)).contains("iCCP", "sRGB", "gAMA");

        byte[] out = process(png);
        assertThat(ImageFixtures.pngChunkTypes(out)).contains("iCCP").doesNotContain("sRGB", "gAMA");

        byte[] srgbOnly = ImageFixtures.withChunkAfterHeader(ImageFixtures.png(8, 8), ImageFixtures.chunk("sRGB", new byte[]{0}));
        assertThat(ImageFixtures.pngChunkTypes(process(srgbOnly))).containsOnlyOnce("sRGB");
    }

    @Test
    @DisplayName("다시 인코딩한 결과가 상한을 넘으면 그 자리에서 멈추고 '넘습니다' 400 — PNG(라이터가 감싸 던짐) · JPEG 둘 다")
    void encodedOutputOverLimitIsTooLarge() {
        ImageProcessor small = new ImageProcessor(10_000);
        BufferedImage noise = new BufferedImage(300, 300, BufferedImage.TYPE_3BYTE_BGR);
        new java.util.Random(1).nextBytes(((java.awt.image.DataBufferByte) noise.getRaster().getDataBuffer()).getData());
        for (String format : List.of("png", "jpg")) {
            byte[] input = ImageFixtures.encode(noise, format);
            ImageProcessor.Inspected inspected = small.inspect(input.length, new ByteArrayResource(input));
            assertThatThrownBy(() -> small.reencode(inspected, input)).as(format)
                    .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.details().toString()).contains("file", "넘습니다"));
        }
    }

    @Test
    @DisplayName("검사한 것과 다른 바이트를 다시 인코딩하려 하면 400 — 형식 · 가로 · 세로가 검사 결과와 같아야 한다")
    void reencodeRequiresTheInspectedFile() {
        ImageProcessor.Inspected jpeg = inspect(ImageFixtures.jpeg(40, 30));
        assertThatThrownBy(() -> processor.reencode(jpeg, ImageFixtures.png(40, 30))).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> processor.reencode(jpeg, ImageFixtures.jpeg(40, 31))).isInstanceOf(BusinessException.class);
    }

    private ImageProcessor.Inspected inspect(byte[] bytes) {
        return processor.inspect(bytes.length, new ByteArrayResource(bytes));
    }

    private byte[] process(byte[] bytes) {
        return processor.reencode(inspect(bytes), bytes);
    }

    private void assertRejectedForSize(byte[] bytes) {
        assertThatThrownBy(() -> inspect(bytes))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.details().toString()).contains("file", "픽셀 이하"));
    }

    private void assertRejected(byte[] bytes) {
        assertThatThrownBy(() -> process(bytes))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.details().toString()).contains("file"));
    }
}

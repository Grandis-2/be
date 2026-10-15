package com.grandis.nova.catalog.image;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * PNG 의 색 정보 청크(iCCP · sRGB · gAMA · cHRM)를 원본에서 다시 인코딩한 파일로 옮긴다. ImageIO 의 PNG 리더는 iCCP 를 픽셀에 반영하지 않고
 * (실측: 선형 RGB 프로파일을 단 128 회색이 메타데이터를 읽든 안 읽든 128 로 풀린다) 라이터는 그 청크를 쓰지 않아, 그냥 다시 인코딩하면
 * Display-P3 같은 사진의 색이 바뀐다. 청크는 압축을 풀지 않고 바이트(길이 · 종류 · 내용 · CRC) 그대로 옮긴다 — 크기는 원본 상한(10MiB)이 묶는다.
 * JPEG 는 리더가 ICC 를 sRGB 픽셀로 바꿔 풀어(실측: 같은 회색이 188) 옮길 것이 없다.
 */
final class PngColorChunks {

    private static final Set<String> COLOR = Set.of("iCCP", "sRGB", "gAMA", "cHRM");
    private static final int SIGNATURE = 8;

    private PngColorChunks() {
    }

    /** IDAT 앞의 색 청크들(원본 순서). 구조가 깨졌으면 그 앞까지만. */
    static List<byte[]> extract(byte[] png) {
        List<byte[]> chunks = new ArrayList<>();
        int i = SIGNATURE;
        while (i + 12 <= png.length) {
            long length = ((long) (png[i] & 0xFF) << 24) | (png[i + 1] & 0xFF) << 16 | (png[i + 2] & 0xFF) << 8 | (png[i + 3] & 0xFF);
            String type = new String(png, i + 4, 4, StandardCharsets.ISO_8859_1);
            long next = i + 12 + length;
            if (next > png.length || type.equals("IDAT")) {
                break;
            }
            if (COLOR.contains(type)) {
                byte[] chunk = new byte[(int) (next - i)];
                System.arraycopy(png, i, chunk, 0, chunk.length);
                chunks.add(chunk);
            }
            i = (int) next;
        }
        return chunks;
    }

    /** IHDR 바로 뒤에 청크들을 끼운다. 라이터가 쓴 파일은 IHDR 이 맨 앞(서명 뒤 25바이트)이다. */
    static byte[] insertAfterHeader(byte[] png, List<byte[]> chunks) {
        if (chunks.isEmpty()) {
            return png;
        }
        int headerEnd = SIGNATURE + 12 + 13;
        ByteArrayOutputStream out = new ByteArrayOutputStream(png.length + chunks.stream().mapToInt(c -> c.length).sum());
        out.write(png, 0, headerEnd);
        chunks.forEach(out::writeBytes);
        out.write(png, headerEnd, png.length - headerEnd);
        return out.toByteArray();
    }
}

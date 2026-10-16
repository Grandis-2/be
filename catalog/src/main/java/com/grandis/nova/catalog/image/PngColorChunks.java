package com.grandis.nova.catalog.image;

import java.awt.color.ICC_Profile;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.CRC32;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.Inflater;

/**
 * PNG 의 색 정보를 원본에서 다시 인코딩한 파일로 옮긴다. ImageIO 의 PNG 리더는 iCCP 를 픽셀에 반영하지 않고
 * (실측: 선형 RGB 프로파일을 단 128 회색이 메타데이터를 읽든 안 읽든 128 로 풀린다) 라이터는 색 청크를 쓰지 않아, 그냥 다시 인코딩하면
 * Display-P3 같은 사진의 색이 바뀐다. JPEG 는 리더가 ICC 를 sRGB 픽셀로 바꿔 풀어(실측: 같은 회색이 188) 옮길 것이 없다.
 *
 * 청크를 바이트 그대로 옮기지 않고 검사한 내용으로 새로 쓴다(길이 · CRC · 압축을 새로 만든다).
 * <ul>
 *   <li>sRGB(1바이트) · gAMA(4바이트) · cHRM(32바이트)은 정해진 길이일 때만 내용을 새 청크로 쓴다. 길이가 다르면 버린다.</li>
 *   <li>iCCP 는 {@link #MAX_PROFILE_BYTES} 까지만 풀어 ICC 프로파일로 읽히는지(헤더 · 태그 표) 보고, 읽힌 프로파일을 다시 압축해 새 청크로
 *       쓴다. 읽히지 않거나 크면 {@link IllegalArgumentException}(업로드는 400). 프로파일 뒤에 덧붙인 바이트는 잘리지만 태그 내용(설명 문자열 등)은
 *       검사하지 않고 옮긴다(리뷰 실측).</li>
 *   <li>iCCP 가 있으면 sRGB 는 쓰지 않는다 — 둘을 함께 두지 않고, 넓은 색역(Display-P3 등) 프로파일을 잃지 않는 쪽을 남긴다.
 *       cICP(HDR 색 정보) 등 그 밖의 색 청크는 옮기지 않는다.</li>
 * </ul>
 */
final class PngColorChunks {

    /** 풀린 ICC 프로파일의 상한. 보통 프로파일은 수 KB 다. */
    static final int MAX_PROFILE_BYTES = 1024 * 1024;
    private static final int SIGNATURE = 8;

    private PngColorChunks() {
    }

    /** IDAT 앞의 색 청크를 검사해 새로 만든 청크들(sRGB 또는 iCCP 하나 · gAMA · cHRM). 구조가 깨졌으면 그 앞까지만 본다. */
    static List<byte[]> extract(byte[] png) {
        byte[] srgb = null;
        byte[] gama = null;
        byte[] chrm = null;
        byte[] iccp = null;
        int i = SIGNATURE;
        while (i + 12 <= png.length) {
            long length = ((long) (png[i] & 0xFF) << 24) | (png[i + 1] & 0xFF) << 16 | (png[i + 2] & 0xFF) << 8 | (png[i + 3] & 0xFF);
            String type = new String(png, i + 4, 4, StandardCharsets.ISO_8859_1);
            long next = i + 12 + length;
            if (next > png.length || type.equals("IDAT")) {
                break;
            }
            int data = i + 8;
            switch (type) {
                case "sRGB" -> srgb = length == 1 && srgb == null ? slice(png, data, 1) : srgb;
                case "gAMA" -> gama = length == 4 && gama == null ? slice(png, data, 4) : gama;
                case "cHRM" -> chrm = length == 32 && chrm == null ? slice(png, data, 32) : chrm;
                case "iCCP" -> iccp = iccp == null ? slice(png, data, (int) length) : iccp;
                default -> { }
            }
            i = (int) next;
        }
        List<byte[]> chunks = new ArrayList<>();
        if (iccp == null && srgb != null) {
            chunks.add(chunk("sRGB", srgb));
        }
        if (gama != null) {
            chunks.add(chunk("gAMA", gama));
        }
        if (chrm != null) {
            chunks.add(chunk("cHRM", chrm));
        }
        if (iccp != null) {
            chunks.add(chunk("iCCP", iccpData(profile(iccp))));
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

    /** iCCP 내용(이름 · 0 · 압축 방식 · zlib 프로파일)에서 상한까지 풀어 읽은 프로파일. */
    private static ICC_Profile profile(byte[] iccp) {
        int nul = 0;
        while (nul < iccp.length && nul < 80 && iccp[nul] != 0) {
            nul++;
        }
        if (nul == 0 || nul >= 80 || nul + 2 > iccp.length || iccp[nul + 1] != 0) {
            throw new IllegalArgumentException("iCCP header");
        }
        Inflater inflater = new Inflater();
        try {
            inflater.setInput(iccp, nul + 2, iccp.length - nul - 2);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            while (!inflater.finished()) {
                int n = inflater.inflate(buffer);
                if (n == 0 && (inflater.needsInput() || inflater.needsDictionary())) {
                    throw new IllegalArgumentException("iCCP truncated");
                }
                out.write(buffer, 0, n);
                if (out.size() > MAX_PROFILE_BYTES) {
                    throw new IllegalArgumentException("iCCP too large");
                }
            }
            return ICC_Profile.getInstance(out.toByteArray());
        } catch (DataFormatException e) {
            throw new IllegalArgumentException("iCCP data", e);
        } finally {
            inflater.end();
        }
    }

    private static byte[] iccpData(ICC_Profile profile) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes("ICC Profile".getBytes(StandardCharsets.ISO_8859_1));
        out.write(0);
        out.write(0);  // 압축 방식 0(zlib)
        // 넘긴 Deflater 는 스트림을 닫아도 닫히지 않는다(JDK 25 실측) — 같이 닫아 네이티브 압축기를 GC 에 맡기지 않는다
        try (Deflater compressor = new Deflater(Deflater.BEST_COMPRESSION);
             DeflaterOutputStream deflate = new DeflaterOutputStream(out, compressor)) {
            deflate.write(profile.getData());
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
        return out.toByteArray();
    }

    private static byte[] slice(byte[] bytes, int from, int length) {
        byte[] out = new byte[length];
        System.arraycopy(bytes, from, out, 0, length);
        return out;
    }

    private static byte[] chunk(String type, byte[] data) {
        byte[] typeAndData = new byte[4 + data.length];
        System.arraycopy(type.getBytes(StandardCharsets.ISO_8859_1), 0, typeAndData, 0, 4);
        System.arraycopy(data, 0, typeAndData, 4, data.length);
        CRC32 crc = new CRC32();
        crc.update(typeAndData);
        long c = crc.getValue();
        int n = data.length;
        ByteArrayOutputStream out = new ByteArrayOutputStream(12 + n);
        out.writeBytes(new byte[]{(byte) (n >>> 24), (byte) (n >>> 16), (byte) (n >>> 8), (byte) n});
        out.writeBytes(typeAndData);
        out.writeBytes(new byte[]{(byte) (c >>> 24), (byte) (c >>> 16), (byte) (c >>> 8), (byte) c});
        return out.toByteArray();
    }
}

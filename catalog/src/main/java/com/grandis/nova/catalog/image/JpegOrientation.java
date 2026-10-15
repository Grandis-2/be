package com.grandis.nova.catalog.image;

/**
 * JPEG EXIF 의 방향(Orientation, 태그 0x0112) 값. ImageIO 는 이 값을 픽셀에 반영하지 않고(실측: Orientation=6 인 400×300 이 400×300 으로
 * 풀린다), 다시 인코딩하면 EXIF 가 빠져 세로 사진이 눕혀 저장된다 — 그래서 직접 읽어 픽셀을 돌린다.
 * 메타데이터 파서(ImageIO 메타데이터 트리)를 쓰지 않고 SOS 앞 마커만 훑는다. 읽지 못하면 1(그대로)이다 — 방향은 표시 보정이라 실패로 막지 않는다.
 */
final class JpegOrientation {

    private static final int TAG_ORIENTATION = 0x0112;
    private static final int TYPE_SHORT = 3;

    private JpegOrientation() {
    }

    /** 1 ~ 8. 없거나 읽지 못하면 1. */
    static int of(byte[] jpeg) {
        int i = 2;  // SOI 뒤
        while (i + 4 <= jpeg.length) {
            if (u8(jpeg, i) != 0xFF) {
                return 1;
            }
            int marker = u8(jpeg, i + 1);
            if (marker == 0xFF) {  // 채움 바이트
                i++;
                continue;
            }
            if (marker == 0xDA || marker == 0xD9) {  // SOS · EOI — 그 뒤는 압축 데이터
                return 1;
            }
            int length = u16(jpeg, i + 2, false);
            if (length < 2 || i + 2 + length > jpeg.length) {
                return 1;
            }
            int start = i + 4;
            if (marker == 0xE1 && length >= 8 && isExif(jpeg, start)) {
                return fromTiff(jpeg, start + 6, i + 2 + length);
            }
            i += 2 + length;
        }
        return 1;
    }

    private static boolean isExif(byte[] b, int at) {
        return b[at] == 'E' && b[at + 1] == 'x' && b[at + 2] == 'i' && b[at + 3] == 'f' && b[at + 4] == 0 && b[at + 5] == 0;
    }

    /** TIFF 머리 → IFD0 에서 방향 태그. [tiff, end) 밖은 읽지 않는다. */
    private static int fromTiff(byte[] b, int tiff, int end) {
        if (tiff + 8 > end) {
            return 1;
        }
        boolean little;
        if (b[tiff] == 'I' && b[tiff + 1] == 'I') {
            little = true;
        } else if (b[tiff] == 'M' && b[tiff + 1] == 'M') {
            little = false;
        } else {
            return 1;
        }
        long ifd = tiff + u32(b, tiff + 4, little);
        if (ifd + 2 > end) {
            return 1;
        }
        int count = u16(b, (int) ifd, little);
        for (int n = 0; n < count; n++) {
            long entry = ifd + 2 + 12L * n;
            if (entry + 12 > end) {
                return 1;
            }
            int e = (int) entry;
            if (u16(b, e, little) == TAG_ORIENTATION) {
                if (u16(b, e + 2, little) != TYPE_SHORT) {
                    return 1;
                }
                int value = u16(b, e + 8, little);  // SHORT 값은 값 칸의 앞 2바이트
                return value >= 1 && value <= 8 ? value : 1;
            }
        }
        return 1;
    }

    private static int u8(byte[] b, int at) {
        return b[at] & 0xFF;
    }

    private static int u16(byte[] b, int at, boolean little) {
        return little ? u8(b, at) | u8(b, at + 1) << 8 : u8(b, at) << 8 | u8(b, at + 1);
    }

    private static long u32(byte[] b, int at, boolean little) {
        return little
                ? (u8(b, at) | u8(b, at + 1) << 8 | u8(b, at + 2) << 16 | (long) u8(b, at + 3) << 24)
                : ((long) u8(b, at) << 24 | u8(b, at + 1) << 16 | u8(b, at + 2) << 8 | u8(b, at + 3));
    }
}

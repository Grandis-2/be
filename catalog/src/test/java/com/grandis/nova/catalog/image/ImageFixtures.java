package com.grandis.nova.catalog.image;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriter;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import java.awt.color.ColorSpace;
import java.awt.color.ICC_Profile;
import java.awt.image.BufferedImage;
import java.awt.image.DataBuffer;
import java.awt.image.Raster;
import java.awt.image.WritableRaster;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.CRC32;
import java.util.zip.DeflaterOutputStream;

/** 시험용 사진 파일. ImageIO 로 만들고, ImageIO 가 쓰지 않는 조각(EXIF · iCCP · 거짓 헤더)은 바이트로 끼운다. */
final class ImageFixtures {

    private ImageFixtures() {
    }

    static BufferedImage rgb(int w, int h) {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_3BYTE_BGR);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                img.setRGB(x, y, (x * 7919 + y * 104729) & 0xFFFFFF);
            }
        }
        return img;
    }

    static byte[] jpeg(int w, int h) {
        return encode(rgb(w, h), "jpg");
    }

    static byte[] png(int w, int h) {
        return encode(rgb(w, h), "png");
    }

    static byte[] encode(BufferedImage img, String format) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            if (!ImageIO.write(img, format, out)) {
                throw new IllegalStateException("no writer for " + format);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out.toByteArray();
    }

    /** SOI 뒤에 EXIF APP1(IFD0 에 Orientation 하나)을 끼운다. bigEndian 이면 MM, 아니면 II. */
    static byte[] withExifOrientation(byte[] jpeg, int orientation, boolean bigEndian) {
        byte[] tiff = bigEndian
                ? new byte[]{'M', 'M', 0, 42, 0, 0, 0, 8, 0, 1, 0x01, 0x12, 0, 3, 0, 0, 0, 1, 0, (byte) orientation, 0, 0, 0, 0, 0, 0}
                : new byte[]{'I', 'I', 42, 0, 8, 0, 0, 0, 1, 0, 0x12, 0x01, 3, 0, 1, 0, 0, 0, (byte) orientation, 0, 0, 0, 0, 0, 0, 0};
        ByteArrayOutputStream segment = new ByteArrayOutputStream();
        segment.writeBytes(new byte[]{'E', 'x', 'i', 'f', 0, 0});
        segment.writeBytes(tiff);
        int length = segment.size() + 2;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(jpeg, 0, 2);
        out.writeBytes(new byte[]{(byte) 0xFF, (byte) 0xE1, (byte) (length >> 8), (byte) length});
        out.writeBytes(segment.toByteArray());
        out.write(jpeg, 2, jpeg.length - 2);
        return out.toByteArray();
    }

    /** JPEG 의 마커(SOS 앞)가 있는지. */
    static boolean hasJpegMarker(byte[] jpeg, int marker) {
        for (int i = 2; i + 3 < jpeg.length; ) {
            int m = jpeg[i + 1] & 0xFF;
            if ((jpeg[i] & 0xFF) != 0xFF || m == 0xDA) {
                return false;
            }
            if (m == marker) {
                return true;
            }
            i += 2 + (((jpeg[i + 2] & 0xFF) << 8) | (jpeg[i + 3] & 0xFF));
        }
        return false;
    }

    /** CMYK 4채널 JPEG(라이터에 래스터를 그대로 넘긴다). */
    static byte[] cmykJpeg(int w, int h) {
        WritableRaster raster = Raster.createInterleavedRaster(DataBuffer.TYPE_BYTE, w, h, 4, null);
        ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (MemoryCacheImageOutputStream out = new MemoryCacheImageOutputStream(bytes)) {
            writer.setOutput(out);
            writer.write(null, new IIOImage(raster, null, null), writer.getDefaultWriteParam());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } finally {
            writer.dispose();
        }
        return bytes.toByteArray();
    }

    static byte[] chunk(String type, byte[] data) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int n = data.length;
        out.writeBytes(new byte[]{(byte) (n >>> 24), (byte) (n >>> 16), (byte) (n >>> 8), (byte) n});
        byte[] typeAndData = new byte[4 + n];
        System.arraycopy(type.getBytes(StandardCharsets.ISO_8859_1), 0, typeAndData, 0, 4);
        System.arraycopy(data, 0, typeAndData, 4, n);
        out.writeBytes(typeAndData);
        CRC32 crc = new CRC32();
        crc.update(typeAndData);
        long c = crc.getValue();
        out.writeBytes(new byte[]{(byte) (c >>> 24), (byte) (c >>> 16), (byte) (c >>> 8), (byte) c});
        return out.toByteArray();
    }

    /** 선형 RGB 프로파일의 iCCP 청크. */
    static byte[] iccpChunk() {
        ByteArrayOutputStream z = new ByteArrayOutputStream();
        try (DeflaterOutputStream d = new DeflaterOutputStream(z)) {
            d.write(ICC_Profile.getInstance(ColorSpace.CS_LINEAR_RGB).getData());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        ByteArrayOutputStream data = new ByteArrayOutputStream();
        data.writeBytes("linear".getBytes(StandardCharsets.ISO_8859_1));
        data.write(0);
        data.write(0);
        data.writeBytes(z.toByteArray());
        return chunk("iCCP", data.toByteArray());
    }

    /** IHDR 바로 뒤에 청크를 끼운다. */
    static byte[] withChunkAfterHeader(byte[] png, byte[] chunk) {
        int headerEnd = 8 + 12 + 13;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(png, 0, headerEnd);
        out.writeBytes(chunk);
        out.write(png, headerEnd, png.length - headerEnd);
        return out.toByteArray();
    }

    /** 청크 종류 목록(순서대로). */
    static List<String> pngChunkTypes(byte[] png) {
        List<String> types = new ArrayList<>();
        for (int i = 8; i + 12 <= png.length; ) {
            int n = ((png[i] & 0xFF) << 24) | ((png[i + 1] & 0xFF) << 16) | ((png[i + 2] & 0xFF) << 8) | (png[i + 3] & 0xFF);
            types.add(new String(png, i + 4, 4, StandardCharsets.ISO_8859_1));
            i += 12 + n;
        }
        return types;
    }

    /** 헤더(IHDR)만 w × h 라고 말하고 픽셀 데이터는 1×1 인 PNG — 풀면 깨지고, 헤더만 읽으면 w × h 다. */
    static byte[] pngClaiming(int w, int h) {
        byte[] real = png(1, 1);
        byte[] ihdr = new byte[13];
        ihdr[0] = (byte) (w >>> 24); ihdr[1] = (byte) (w >>> 16); ihdr[2] = (byte) (w >>> 8); ihdr[3] = (byte) w;
        ihdr[4] = (byte) (h >>> 24); ihdr[5] = (byte) (h >>> 16); ihdr[6] = (byte) (h >>> 8); ihdr[7] = (byte) h;
        System.arraycopy(real, 8 + 8 + 8, ihdr, 8, 5);  // 비트 깊이 · 색 종류 · 압축 · 필터 · 인터레이스는 원본 그대로
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(real, 0, 8);
        out.writeBytes(chunk("IHDR", ihdr));
        out.write(real, 8 + 12 + 13, real.length - (8 + 12 + 13));
        return out.toByteArray();
    }
}

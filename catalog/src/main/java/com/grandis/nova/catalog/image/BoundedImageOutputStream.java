package com.grandis.nova.catalog.image;

import javax.imageio.stream.ImageOutputStreamImpl;
import java.io.IOException;
import java.util.Arrays;

/**
 * 다시 인코딩한 파일을 받는 메모리 출력 — 상한을 넘는 쓰기가 오면 그 자리에서 {@link TooLarge} 를 던진다. 다 쓴 뒤에 크기를 보면 상한보다 큰
 * 출력이 힙에 다 쌓인 뒤라(실측: 노이즈 사진을 품질 0.5 로 저장한 10MiB 이하 JPEG 을 0.9 로 다시 쓰면 넘친다) 한 장의 메모리 상한이 깨진다.
 * 버퍼 하나에 바로 쓰고 되감기(PNG 라이터가 청크 길이를 고쳐 쓴다)를 지원한다 — 캐시 스트림 + 바이트 배열로 두 벌 들지 않는다.
 */
final class BoundedImageOutputStream extends ImageOutputStreamImpl {

    /** 상한을 넘는 쓰기. */
    static final class TooLarge extends IOException {
        TooLarge() {
            super("encoded image exceeds the limit", null);
        }
    }

    private final int limit;
    private byte[] buffer = new byte[64 * 1024];
    private int length;

    BoundedImageOutputStream(int limit) {
        this.limit = limit;
    }

    @Override
    public void write(int b) throws IOException {
        flushBits();
        ensure(streamPos + 1);
        buffer[(int) streamPos++] = (byte) b;
        length = Math.max(length, (int) streamPos);
    }

    @Override
    public void write(byte[] b, int off, int len) throws IOException {
        flushBits();
        ensure(streamPos + len);
        System.arraycopy(b, off, buffer, (int) streamPos, len);
        streamPos += len;
        length = Math.max(length, (int) streamPos);
    }

    @Override
    public int read() throws IOException {
        checkClosed();
        bitOffset = 0;
        return streamPos >= length ? -1 : buffer[(int) streamPos++] & 0xFF;
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
        checkClosed();
        bitOffset = 0;
        if (streamPos >= length) {
            return -1;
        }
        int n = (int) Math.min(len, length - streamPos);
        System.arraycopy(buffer, (int) streamPos, b, off, n);
        streamPos += n;
        return n;
    }

    @Override
    public long length() {
        return length;
    }

    /** 쓴 바이트. */
    byte[] toByteArray() {
        return Arrays.copyOf(buffer, length);
    }

    private void ensure(long size) throws IOException {
        if (size > limit) {
            throw new TooLarge();
        }
        if (size > buffer.length) {
            buffer = Arrays.copyOf(buffer, (int) Math.min(limit, Math.max(size, (long) buffer.length * 2)));
        }
    }
}

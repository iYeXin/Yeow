package yeow.service;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

import static org.junit.jupiter.api.Assertions.*;

/** Native Service 帧协议：往返、空 body、分块、超限拒绝、EOF。 */
class NativeProtocolTest {

    @Test
    void roundTrip() throws Exception {
        var out = new ByteArrayOutputStream();
        String header = "{\"type\":\"request\",\"id\":\"r1\",\"contentType\":\"application/json\"}";
        byte[] body = { 1, 2, 3, 4, 5 };
        NativeProtocol.write(out, header, body);

        var m = NativeProtocol.read(new ByteArrayInputStream(out.toByteArray()));
        assertEquals(header, m.header());
        assertArrayEquals(body, m.body());
    }

    @Test
    void emptyAndNullBody() throws Exception {
        var out = new ByteArrayOutputStream();
        NativeProtocol.write(out, "{}", new byte[0]);
        // headerLen(4) + "{}"(2) + terminator(4)
        assertEquals(10, out.size());
        var m = NativeProtocol.read(new ByteArrayInputStream(out.toByteArray()));
        assertEquals("{}", m.header());
        assertEquals(0, m.body().length);

        var out2 = new ByteArrayOutputStream();
        NativeProtocol.write(out2, "{}", (byte[]) null);
        assertEquals(0, NativeProtocol.read(new ByteArrayInputStream(out2.toByteArray())).body().length);
    }

    @Test
    void multiChunkBody() throws Exception {
        var out = new ByteArrayOutputStream();
        byte[] h = "{\"type\":\"response\"}".getBytes();
        out.write(new byte[] { 0, 0, 0, (byte) h.length }); out.write(h);
        out.write(new byte[] { 0, 0, 0, 2 }); out.write(new byte[] { 9, 9 });
        out.write(new byte[] { 0, 0, 0, 3 }); out.write(new byte[] { 7, 7, 7 });
        out.write(new byte[] { 0, 0, 0, 0 });

        var m = NativeProtocol.read(new ByteArrayInputStream(out.toByteArray()));
        assertArrayEquals(new byte[] { 9, 9, 7, 7, 7 }, m.body());
    }

    @Test
    void cleanEofReturnsNull() throws Exception {
        assertNull(NativeProtocol.read(new ByteArrayInputStream(new byte[0])));
    }

    @Test
    void oversizedHeaderRejected() throws Exception {
        var out = new ByteArrayOutputStream();
        out.write(new byte[] { 0, 0x01, (byte) 0x86, (byte) 0xA0 }); // 100000 > MAX_HEADER_BYTES
        assertThrows(IOException.class, () -> NativeProtocol.read(new ByteArrayInputStream(out.toByteArray())));
    }

    @Test
    void oversizedBodyRejected() throws Exception {
        var out = new ByteArrayOutputStream();
        byte[] h = "{}".getBytes();
        out.write(new byte[] { 0, 0, 0, (byte) h.length }); out.write(h);
        int big = NativeProtocol.MAX_BODY_BYTES + 1;
        out.write(new byte[] { (byte) (big >>> 24), (byte) (big >>> 16), (byte) (big >>> 8), (byte) big });
        assertThrows(IOException.class, () -> NativeProtocol.read(new ByteArrayInputStream(out.toByteArray())));
    }

    @Test
    void truncatedBodyThrows() throws Exception {
        var out = new ByteArrayOutputStream();
        byte[] h = "{}".getBytes();
        out.write(new byte[] { 0, 0, 0, (byte) h.length }); out.write(h);
        out.write(new byte[] { 0, 0, 0, 5 }); out.write(new byte[] { 1, 2 }); // declares 5, provides 2, no terminator
        assertThrows(IOException.class, () -> NativeProtocol.read(new ByteArrayInputStream(out.toByteArray())));
    }
}

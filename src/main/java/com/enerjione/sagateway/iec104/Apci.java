package com.enerjione.sagateway.iec104;

/**
 * APCI cerceveleri: {@code 0x68 LEN C1 C2 C3 C4 [ASDU]}. LEN = 4 + ASDU boyu,
 * en cok 253. Sira numaralari 15 bit (0..32767).
 */
public final class Apci {

    public static final int START = 0x68;
    public static final int MAX_LENGTH = 253;
    public static final int MODULO = 32768;

    public static final int STARTDT_ACT = 0x07;
    public static final int STARTDT_CON = 0x0B;
    public static final int STOPDT_ACT = 0x13;
    public static final int STOPDT_CON = 0x23;
    public static final int TESTFR_ACT = 0x43;
    public static final int TESTFR_CON = 0x83;

    public enum Kind { I, S, U }

    /** Cozulmus cerceve. U'da {@code function} = C1; I'da {@code asdu} dolu. */
    public record Frame(Kind kind, int sendSeq, int recvSeq, int function, byte[] asdu) {
    }

    private Apci() {
    }

    public static byte[] u(int function) {
        return new byte[] {(byte) START, 4, (byte) function, 0, 0, 0};
    }

    public static byte[] s(int recvSeq) {
        return new byte[] {(byte) START, 4, 0x01, 0, (byte) ((recvSeq << 1) & 0xFE), (byte) ((recvSeq >> 7) & 0xFF)};
    }

    public static byte[] i(int sendSeq, int recvSeq, byte[] asdu) {
        if (asdu.length > MAX_LENGTH - 4) {
            throw new IllegalArgumentException("ASDU cok uzun: " + asdu.length);
        }
        byte[] out = new byte[6 + asdu.length];
        out[0] = (byte) START;
        out[1] = (byte) (4 + asdu.length);
        out[2] = (byte) ((sendSeq << 1) & 0xFE);
        out[3] = (byte) ((sendSeq >> 7) & 0xFF);
        out[4] = (byte) ((recvSeq << 1) & 0xFE);
        out[5] = (byte) ((recvSeq >> 7) & 0xFF);
        System.arraycopy(asdu, 0, out, 6, asdu.length);
        return out;
    }

    /**
     * {@code b[off]} = 0x68 ile baslayan, {@code len} = LEN alanindaki deger olan
     * tam bir cerceveyi cozer (kontrol alani + ASDU).
     */
    public static Frame parse(byte[] b, int off, int len) {
        int c1 = b[off + 2] & 0xFF;
        int c2 = b[off + 3] & 0xFF;
        int c3 = b[off + 4] & 0xFF;
        int c4 = b[off + 5] & 0xFF;
        if ((c1 & 0x01) == 0) {
            int ns = (c1 >> 1) | (c2 << 7);
            int nr = (c3 >> 1) | (c4 << 7);
            byte[] asdu = new byte[len - 4];
            System.arraycopy(b, off + 6, asdu, 0, asdu.length);
            return new Frame(Kind.I, ns, nr, 0, asdu);
        }
        if ((c1 & 0x03) == 0x01) {
            int nr = (c3 >> 1) | (c4 << 7);
            return new Frame(Kind.S, 0, nr, 0, null);
        }
        return new Frame(Kind.U, 0, 0, c1, null);
    }

    /** a ile b arasindaki ileri fark (mod 32768). */
    public static int distance(int from, int to) {
        return Math.floorMod(to - from, MODULO);
    }
}

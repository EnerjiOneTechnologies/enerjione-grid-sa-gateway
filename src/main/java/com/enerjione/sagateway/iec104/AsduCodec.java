package com.enerjione.sagateway.iec104;

import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

/**
 * ASDU cozme / kurma. Alan boylari 104'te standart olarak COT 2, CA 2, IOA 3
 * bayttir; eski ya da ozel cihazlar icin degistirilebilir.
 */
public final class AsduCodec {

    /** Cozulemeyen ASDU (kisa govde). Baglanti kesilmez, ASDU atlanir. */
    public static final class MalformedAsduException extends Exception {
        public MalformedAsduException(String message) {
            super(message);
        }
    }

    private final int cotSize;
    private final int caSize;
    private final int ioaSize;
    private final ZoneId zone;

    public AsduCodec(int cotSize, int caSize, int ioaSize, ZoneId zone) {
        if (cotSize < 1 || cotSize > 2) throw new IllegalArgumentException("cotSize 1 ya da 2");
        if (caSize < 1 || caSize > 2) throw new IllegalArgumentException("caSize 1 ya da 2");
        if (ioaSize < 1 || ioaSize > 3) throw new IllegalArgumentException("ioaSize 1..3");
        this.cotSize = cotSize;
        this.caSize = caSize;
        this.ioaSize = ioaSize;
        this.zone = zone;
    }

    public static AsduCodec standard(ZoneId zone) {
        return new AsduCodec(2, 2, 3, zone);
    }

    public int headerSize() {
        return 2 + cotSize + caSize;
    }

    public Asdu decode(byte[] b, int off, int len) throws MalformedAsduException {
        if (len < headerSize()) {
            throw new MalformedAsduException("ASDU basligi kisa: " + len + " bayt");
        }
        int end = off + len;
        int p = off;
        int typeCode = b[p++] & 0xFF;
        int vsq = b[p++] & 0xFF;
        boolean sq = (vsq & 0x80) != 0;
        int n = vsq & 0x7F;
        int cotByte = b[p++] & 0xFF;
        int cause = cotByte & 0x3F;
        boolean negative = (cotByte & 0x40) != 0;
        boolean test = (cotByte & 0x80) != 0;
        int originator = 0;
        if (cotSize == 2) {
            originator = b[p++] & 0xFF;
        }
        int ca = b[p++] & 0xFF;
        if (caSize == 2) {
            ca |= (b[p++] & 0xFF) << 8;
        }

        TypeId type = TypeId.of(typeCode);
        if (type == null || n == 0) {
            return new Asdu(typeCode, type, sq, cause, negative, test, originator, ca, List.of());
        }
        int el = type.elementSize;
        int need = sq ? ioaSize + n * el : n * (ioaSize + el);
        if (end - p < need) {
            throw new MalformedAsduException(
                    type.name() + " N=" + n + ": " + need + " bayt beklendi, " + (end - p) + " var");
        }
        List<InfoObject> objects = new ArrayList<>(n);
        int ioa = 0;
        for (int i = 0; i < n; i++) {
            if (!sq || i == 0) {
                ioa = readIoa(b, p);
                p += ioaSize;
            } else {
                ioa++;
            }
            objects.add(decodeElement(type, ioa, b, p));
            p += el;
        }
        return new Asdu(typeCode, type, sq, cause, negative, test, originator, ca, List.copyOf(objects));
    }

    private int readIoa(byte[] b, int p) {
        int v = 0;
        for (int i = 0; i < ioaSize; i++) {
            v |= (b[p + i] & 0xFF) << (8 * i);
        }
        return v;
    }

    private static int qds(byte q) {
        return q & (InfoObject.IV | InfoObject.NT | InfoObject.SB | InfoObject.BL | InfoObject.OV);
    }

    private static int int16(byte[] b, int p) {
        return (short) ((b[p] & 0xFF) | ((b[p + 1] & 0xFF) << 8));
    }

    private static int int32(byte[] b, int p) {
        return (b[p] & 0xFF) | ((b[p + 1] & 0xFF) << 8) | ((b[p + 2] & 0xFF) << 16) | ((b[p + 3] & 0xFF) << 24);
    }

    private InfoObject decodeElement(TypeId t, int ioa, byte[] b, int p) {
        double value;
        int flags;
        switch (t) {
            case M_SP_NA_1, M_SP_TB_1 -> {
                value = b[p] & 0x01;
                flags = b[p] & 0xF0;
            }
            case M_DP_NA_1, M_DP_TB_1 -> {
                value = b[p] & 0x03;
                flags = b[p] & 0xF0;
            }
            case M_ST_NA_1, M_ST_TB_1 -> {
                int v = b[p] & 0x7F;
                if ((v & 0x40) != 0) v -= 128;
                value = v;
                flags = qds(b[p + 1]) | ((b[p] & 0x80) != 0 ? InfoObject.TRANSIENT : 0);
            }
            case M_BO_NA_1, M_BO_TB_1 -> {
                value = Integer.toUnsignedLong(int32(b, p));
                flags = qds(b[p + 4]);
            }
            case M_ME_NA_1, M_ME_TD_1 -> {
                value = int16(b, p) / 32768.0;
                flags = qds(b[p + 2]);
            }
            case M_ME_NB_1, M_ME_TE_1 -> {
                value = int16(b, p);
                flags = qds(b[p + 2]);
            }
            case M_ME_NC_1, M_ME_TF_1 -> {
                // float32 -> double: 230.65f "230.64999389648438" olmasin diye ondalik yazimdan gecer.
                value = Double.parseDouble(Float.toString(Float.intBitsToFloat(int32(b, p))));
                flags = qds(b[p + 4]);
            }
            case M_ME_ND_1 -> {
                value = int16(b, p) / 32768.0;
                flags = 0;
            }
            case M_IT_NA_1, M_IT_TB_1 -> {
                value = int32(b, p);
                int s = b[p + 4] & 0xFF;
                flags = (s & 0x80) != 0 ? InfoObject.IV : 0;
                if ((s & 0x40) != 0) flags |= InfoObject.ADJUSTED;
                if ((s & 0x20) != 0) flags |= InfoObject.CARRY;
            }
            case C_CS_NA_1 -> {
                Cp56Time2a.Value tv = Cp56Time2a.decode(b, p, zone);
                return new InfoObject(ioa, 0, tv.invalid() ? InfoObject.TIME_INVALID : 0, tv.instant());
            }
            default -> {
                // M_EI_NA_1 (COI), C_IC_NA_1 (QOI), C_CI_NA_1 (QCC): tek bayt niteleyici
                value = b[p] & 0xFF;
                flags = 0;
            }
        }
        Instant time = null;
        if (t.timeTagged) {
            Cp56Time2a.Value tv = Cp56Time2a.decode(b, p + t.elementSize - Cp56Time2a.SIZE, zone);
            time = tv.instant();
            if (tv.invalid()) flags |= InfoObject.TIME_INVALID;
        }
        return new InfoObject(ioa, value, flags, time);
    }

    // ---- komut kurma (istasyona giden) --------------------------------------

    /** Genel sorgu (C_IC_NA_1, QOI 20) ya da grup sorgusu (QOI 21..36). */
    public byte[] interrogation(int cause, int originator, int ca, int qoi) {
        return command(TypeId.C_IC_NA_1, cause, originator, ca, new byte[] {(byte) qoi});
    }

    /** Sayac sorgusu (C_CI_NA_1). QCC 5 = genel sayac okuma, dondurme yok. */
    public byte[] counterInterrogation(int originator, int ca, int qcc) {
        return command(TypeId.C_CI_NA_1, Asdu.COT_ACTIVATION, originator, ca, new byte[] {(byte) qcc});
    }

    private byte[] command(TypeId t, int cause, int originator, int ca, byte[] element) {
        byte[] out = new byte[headerSize() + ioaSize + element.length];
        int p = 0;
        out[p++] = (byte) t.code;
        out[p++] = 0x01;
        out[p++] = (byte) (cause & 0x3F);
        if (cotSize == 2) out[p++] = (byte) originator;
        out[p++] = (byte) (ca & 0xFF);
        if (caSize == 2) out[p++] = (byte) ((ca >> 8) & 0xFF);
        p += ioaSize; // IOA 0
        System.arraycopy(element, 0, out, p, element.length);
        return out;
    }
}

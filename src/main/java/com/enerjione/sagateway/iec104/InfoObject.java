package com.enerjione.sagateway.iec104;

import java.time.Instant;

/**
 * Cozulmus bilgi nesnesi.
 *
 * <p>{@code value}: tek nokta 0/1, cift nokta 0..3 (0 ara, 1 acik, 2 kapali,
 * 3 hatali), kademe -64..63, bit dizisi 0..2^32-1, olcum (normalize degerler
 * -1..1), sayac int32. {@code time}: zaman etiketli tiplerde cihaz zamani
 * (etiket bozuksa null).
 */
public record InfoObject(int ioa, double value, int flags, Instant time) {

    /** Gecersiz (IV). */
    public static final int IV = 0x80;
    /** Guncel degil (NT). */
    public static final int NT = 0x40;
    /** Yerine konmus (SB). */
    public static final int SB = 0x20;
    /** Engellenmis (BL). */
    public static final int BL = 0x10;
    /** Tasma (OV). */
    public static final int OV = 0x01;
    /** Kademe gecis halinde (VTI bit 7). */
    public static final int TRANSIENT = 0x100;
    /** Sayac: tasma (CY). */
    public static final int CARRY = 0x200;
    /** Sayac: ayarlandi (CA). */
    public static final int ADJUSTED = 0x400;
    /** Zaman etiketi gecersiz ya da cozulemedi. */
    public static final int TIME_INVALID = 0x800;

    /** Grid kalitesi: IV, NT ya da OV varsa gecersiz. */
    public boolean good() {
        return (flags & (IV | NT | OV)) == 0;
    }

    public String flagText() {
        StringBuilder sb = new StringBuilder();
        if ((flags & IV) != 0) sb.append("IV ");
        if ((flags & NT) != 0) sb.append("NT ");
        if ((flags & SB) != 0) sb.append("SB ");
        if ((flags & BL) != 0) sb.append("BL ");
        if ((flags & OV) != 0) sb.append("OV ");
        if ((flags & TRANSIENT) != 0) sb.append("T ");
        if ((flags & CARRY) != 0) sb.append("CY ");
        if ((flags & ADJUSTED) != 0) sb.append("CA ");
        if ((flags & TIME_INVALID) != 0) sb.append("ZAMAN-IV ");
        return sb.toString().trim();
    }
}

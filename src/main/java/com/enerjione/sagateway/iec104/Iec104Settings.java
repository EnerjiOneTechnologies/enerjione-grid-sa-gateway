package com.enerjione.sagateway.iec104;

import java.time.ZoneId;

/**
 * Bir istasyon baglantisinin ayarlari. Alanlar Grid SA'daki IEC 104 IED
 * baglantisiyla ayni adlari ve varsayilanlari tasir
 * ({@code app/modules/sa/schemas/protocol.py Iec104Connection}).
 *
 * @param redundantHost yedek adres; birincil baglanamazsa sirayla denenir (null = yok)
 * @param giIntervalSec 0 = genel sorgu yalniz baglaninca
 * @param zone cihazin CP56Time2a etiketlerindeki saat dilimi
 */
public record Iec104Settings(
        String host,
        int port,
        String redundantHost,
        int commonAddress,
        int originatorAddress,
        int k,
        int w,
        int t0,
        int t1,
        int t2,
        int t3,
        int giIntervalSec,
        ZoneId zone) {

    public Iec104Settings {
        if (host == null || host.isBlank()) throw new IllegalArgumentException("host bos");
        if (port < 1 || port > 65535) throw new IllegalArgumentException("port 1..65535");
        if (commonAddress < 1 || commonAddress > 65535) throw new IllegalArgumentException("ortak adres 1..65535");
        if (originatorAddress < 0 || originatorAddress > 255) throw new IllegalArgumentException("kaynak adresi 0..255");
        if (k < 1 || k > 32767 || w < 1 || w > k) throw new IllegalArgumentException("1 <= w <= k <= 32767");
        if (t0 < 1 || t1 < 1 || t2 < 1 || t3 < 1) throw new IllegalArgumentException("zamanlayicilar >= 1 sn");
        if (t2 >= t1) throw new IllegalArgumentException("t2, t1'den kucuk olmali");
        if (giIntervalSec < 0) throw new IllegalArgumentException("genel sorgu araligi >= 0");
        if (zone == null) zone = ZoneId.of("UTC");
    }

    /** Standart 104 varsayilanlari (k 12, w 8, t0 30, t1 15, t2 10, t3 20). */
    public static Iec104Settings defaults(String host, int port, int commonAddress) {
        return new Iec104Settings(host, port, null, commonAddress, 0, 12, 8, 30, 15, 10, 20, 0, ZoneId.of("UTC"));
    }

    public Iec104Settings withGiInterval(int sec) {
        return new Iec104Settings(host, port, redundantHost, commonAddress, originatorAddress, k, w, t0, t1, t2, t3, sec, zone);
    }
}

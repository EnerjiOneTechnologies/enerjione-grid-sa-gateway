package com.enerjione.sagateway.iec104;

import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;

/**
 * CP56Time2a (7 bayt) zaman etiketi.
 *
 * <pre>
 * 0-1  milisaniye (0..59999, little endian)
 * 2    dakika (bit 0-5), IV (bit 7: gecersiz)
 * 3    saat (bit 0-4), SU (bit 7: yaz saati)
 * 4    ayin gunu (bit 0-4), haftanin gunu (bit 5-7)
 * 5    ay (bit 0-3)
 * 6    yil (bit 0-6, 2000 + yil)
 * </pre>
 *
 * Etiket saat dilimi TASIMAZ. Cihaz yerel saat gonderiyorsa {@code zone}
 * o dilim olmalidir; varsayilan UTC.
 */
public final class Cp56Time2a {

    public static final int SIZE = 7;

    private Cp56Time2a() {
    }

    /** Cozulen zaman. {@code instant} alanlar tutarsizsa null olur. */
    public record Value(Instant instant, boolean invalid, boolean summerTime) {
    }

    public static Value decode(byte[] b, int off, ZoneId zone) {
        int ms = (b[off] & 0xFF) | ((b[off + 1] & 0xFF) << 8);
        int minute = b[off + 2] & 0x3F;
        boolean invalid = (b[off + 2] & 0x80) != 0;
        int hour = b[off + 3] & 0x1F;
        boolean summer = (b[off + 3] & 0x80) != 0;
        int day = b[off + 4] & 0x1F;
        int month = b[off + 5] & 0x0F;
        int year = 2000 + (b[off + 6] & 0x7F);
        Instant instant;
        try {
            LocalDateTime ldt = LocalDateTime.of(year, month, day, hour, minute, ms / 1000, (ms % 1000) * 1_000_000);
            instant = ZonedDateTime.of(ldt, zone).toInstant();
        } catch (DateTimeException e) {
            // Ay 0, gun 0, ms > 59999 gibi bozuk etiket: deger yine islenir, zaman yok.
            instant = null;
            invalid = true;
        }
        return new Value(instant, invalid, summer);
    }

    public static void encode(Instant instant, ZoneId zone, byte[] b, int off) {
        ZonedDateTime z = instant.atZone(zone);
        int ms = z.getSecond() * 1000 + z.getNano() / 1_000_000;
        b[off] = (byte) (ms & 0xFF);
        b[off + 1] = (byte) ((ms >> 8) & 0xFF);
        b[off + 2] = (byte) (z.getMinute() & 0x3F);
        b[off + 3] = (byte) (z.getHour() & 0x1F);
        b[off + 4] = (byte) ((z.getDayOfMonth() & 0x1F) | ((z.getDayOfWeek().getValue() & 0x07) << 5));
        b[off + 5] = (byte) (z.getMonthValue() & 0x0F);
        b[off + 6] = (byte) ((z.getYear() - 2000) & 0x7F);
    }
}

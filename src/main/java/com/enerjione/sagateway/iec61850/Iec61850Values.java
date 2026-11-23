package com.enerjione.sagateway.iec61850;

import com.beanit.iec61850bean.BasicDataAttribute;
import com.beanit.iec61850bean.BdaBoolean;
import com.beanit.iec61850bean.BdaDoubleBitPos;
import com.beanit.iec61850bean.BdaFloat32;
import com.beanit.iec61850bean.BdaFloat64;
import com.beanit.iec61850bean.BdaInt128;
import com.beanit.iec61850bean.BdaInt16;
import com.beanit.iec61850bean.BdaInt16U;
import com.beanit.iec61850bean.BdaInt32;
import com.beanit.iec61850bean.BdaInt32U;
import com.beanit.iec61850bean.BdaInt64;
import com.beanit.iec61850bean.BdaInt8;
import com.beanit.iec61850bean.BdaInt8U;
import com.beanit.iec61850bean.BdaQuality;
import com.beanit.iec61850bean.BdaTimestamp;
import java.time.Instant;

/**
 * 61850 temel veri ozniteliklerinin (BDA) Grid nokta degerine cevrilmesi.
 *
 * <p>Not: {@code retrieveModel()} ile okunan modelde ENUM tipler (Beh.stVal,
 * ctlModel...) MMS'te tamsayi gectigi icin {@link BdaInt8} olarak gelir; bu
 * yuzden {@code st} tamsayi kabul eder.
 */
public final class Iec61850Values {

    private Iec61850Values() {
    }

    /**
     * Grid nokta tipine gore sayi. Tip uymuyorsa {@code null} (uyusmazlik);
     * olcumde NaN / sonsuz donebilir, cagiran gecersiz sayar.
     */
    public static Double toPointValue(BasicDataAttribute b, String pointType) {
        if (b == null || pointType == null) return null;
        return switch (pointType) {
            case "sp" -> b instanceof BdaBoolean x ? (x.getValue() ? 1.0 : 0.0) : null;
            // Dbpos: iec61850bean enum sirasi INTERMEDIATE_STATE(0), OFF(1), ON(2), BAD_STATE(3);
            // sira (ordinal) degil, standart deger (getIntValue) kullanilir. 0 ara, 1 acik, 2 kapali, 3 bozuk.
            case "dp" -> b instanceof BdaDoubleBitPos x ? (double) x.getDoubleBitPos().getIntValue() : null;
            case "mv" -> {
                Double f = floating(b);
                yield f != null ? f : integer(b);
            }
            // BCR.actVal INT64; tamsayi ailesinin tamami kabul edilir.
            case "it", "st" -> integer(b);
            default -> null; // txt ve bilinmeyenler: Grid'e gonderilmez
        };
    }

    /** CLI icin: sayiya cevrilebilen her BDA (bool 1/0, Dbpos 0..3, tamsayi, float). */
    public static Double numeric(BasicDataAttribute b) {
        if (b instanceof BdaBoolean x) return x.getValue() ? 1.0 : 0.0;
        if (b instanceof BdaDoubleBitPos x) return (double) x.getDoubleBitPos().getIntValue();
        Double f = floating(b);
        return f != null ? f : integer(b);
    }

    private static Double floating(BasicDataAttribute b) {
        if (b instanceof BdaFloat32 x) {
            Float v = x.getFloat();
            return v == null ? Double.NaN : v.doubleValue();
        }
        if (b instanceof BdaFloat64 x) {
            Double v = x.getDouble();
            return v == null ? Double.NaN : v;
        }
        return null;
    }

    private static Double integer(BasicDataAttribute b) {
        if (b instanceof BdaInt8 x) return (double) x.getValue();
        if (b instanceof BdaInt16 x) return (double) x.getValue();
        if (b instanceof BdaInt32 x) return (double) x.getValue();
        if (b instanceof BdaInt64 x) return (double) x.getValue();
        if (b instanceof BdaInt128 x) return (double) x.getValue();
        if (b instanceof BdaInt8U x) return (double) x.getValue();
        if (b instanceof BdaInt16U x) return (double) x.getValue();
        if (b instanceof BdaInt32U x) return (double) x.getValue();
        return null;
    }

    /**
     * Kalite iyi mi. q yoksa (CF/DC gibi FC'lerde ya da modelde bulunamadiysa)
     * iyi kabul edilir. Validity GOOD -> iyi; INVALID, QUESTIONABLE ve RESERVED
     * gecersiz. Test biti bilerek dikkate alinmaz: test modundaki deger iyi
     * gider (Grid tarafinda ayri gosterim yok).
     */
    public static boolean good(BdaQuality q) {
        return q == null || q.getValidity() == BdaQuality.Validity.GOOD;
    }

    /** Validity adi (gunluk / CLI); q yoksa null. */
    public static String validity(BdaQuality q) {
        return q == null ? null : q.getValidity().name();
    }

    /**
     * Zaman etiketi (UTC). Hic yazilmamis (tum baytlar 0, yani 1970) ya da saat
     * arizasi (ClockFailure) bayrakli etiket null doner. Senkronsuz saat
     * (ClockNotSynchronized) yine gonderilir.
     */
    public static Instant time(BdaTimestamp t) {
        if (t == null) return null;
        byte[] v = t.getValue();
        if (v == null || v.length < 8) return null;
        boolean zero = true;
        for (int i = 0; i < 7; i++) {
            if (v[i] != 0) {
                zero = false;
                break;
            }
        }
        if (zero || t.getClockFailure()) return null;
        return t.getInstant();
    }
}

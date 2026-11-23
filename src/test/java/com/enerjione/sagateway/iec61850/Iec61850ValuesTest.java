package com.enerjione.sagateway.iec61850;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.beanit.iec61850bean.BdaBoolean;
import com.beanit.iec61850bean.BdaDoubleBitPos;
import com.beanit.iec61850bean.BdaDoubleBitPos.DoubleBitPos;
import com.beanit.iec61850bean.BdaFloat32;
import com.beanit.iec61850bean.BdaInt64;
import com.beanit.iec61850bean.BdaInt8;
import com.beanit.iec61850bean.BdaQuality;
import com.beanit.iec61850bean.BdaTimestamp;
import com.beanit.iec61850bean.Fc;
import com.beanit.iec61850bean.ObjectReference;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/** Nokta tipine gore cevirme, kalite ve zaman kurallari. */
class Iec61850ValuesTest {

    private static ObjectReference ref(String s) {
        return new ObjectReference("LD/LN1.X." + s);
    }

    @Test
    void cift_bit_konum_standart_degerleri() {
        BdaDoubleBitPos b = new BdaDoubleBitPos(ref("stVal"), Fc.ST, null, false, false);
        b.setDoubleBitPos(DoubleBitPos.INTERMEDIATE_STATE);
        assertEquals(0.0, Iec61850Values.toPointValue(b, "dp"));
        b.setDoubleBitPos(DoubleBitPos.OFF);
        assertEquals(1.0, Iec61850Values.toPointValue(b, "dp"));
        b.setDoubleBitPos(DoubleBitPos.ON);
        assertEquals(2.0, Iec61850Values.toPointValue(b, "dp"));
        b.setDoubleBitPos(DoubleBitPos.BAD_STATE);
        assertEquals(3.0, Iec61850Values.toPointValue(b, "dp"));
        // Bit duzeni: ON = 10 (0x80), OFF = 01 (0x40).
        b.setValue(new byte[] {(byte) 0x80});
        assertEquals(2.0, Iec61850Values.toPointValue(b, "dp"));
        b.setValue(new byte[] {0x40});
        assertEquals(1.0, Iec61850Values.toPointValue(b, "dp"));
        assertNull(Iec61850Values.toPointValue(b, "sp"));
    }

    @Test
    void tip_aileleri() {
        BdaBoolean bo = new BdaBoolean(ref("stVal"), Fc.ST, null, false, false);
        bo.setValue(true);
        assertEquals(1.0, Iec61850Values.toPointValue(bo, "sp"));
        assertNull(Iec61850Values.toPointValue(bo, "mv"));

        BdaFloat32 f = new BdaFloat32(ref("mag.f"), Fc.MX, null, false, false);
        f.setFloat(230.5f);
        assertEquals(230.5, Iec61850Values.toPointValue(f, "mv"));
        assertNull(Iec61850Values.toPointValue(f, "st"));
        f.setFloat(Float.POSITIVE_INFINITY);
        assertTrue(Double.isInfinite(Iec61850Values.toPointValue(f, "mv")));

        BdaInt64 i = new BdaInt64(ref("actVal"), Fc.ST, null, false, false);
        i.setValue(9_000_000_123L);
        assertEquals(9_000_000_123.0, Iec61850Values.toPointValue(i, "it"));
        assertEquals(9_000_000_123.0, Iec61850Values.toPointValue(i, "mv"));

        BdaInt8 e = new BdaInt8(ref("stVal"), Fc.ST, null, false, false);
        e.setValue((byte) 5);
        assertEquals(5.0, Iec61850Values.toPointValue(e, "st"));
        assertNull(Iec61850Values.toPointValue(e, "txt"));
    }

    @Test
    void kalite_validity() {
        BdaQuality q = new BdaQuality(ref("q"), Fc.ST, null, false);
        assertTrue(Iec61850Values.good(q));
        assertTrue(Iec61850Values.good(null));
        q.setTest(true);
        assertTrue(Iec61850Values.good(q)); // test modu iyi kalir
        q.setValidity(BdaQuality.Validity.INVALID);
        assertFalse(Iec61850Values.good(q));
        q.setValidity(BdaQuality.Validity.QUESTIONABLE);
        assertFalse(Iec61850Values.good(q));
        q.setValidity(BdaQuality.Validity.RESERVED);
        assertFalse(Iec61850Values.good(q));
        q.setValidity(BdaQuality.Validity.GOOD);
        assertTrue(Iec61850Values.good(q));
    }

    @Test
    void zaman_etiketi() {
        BdaTimestamp t = new BdaTimestamp(ref("t"), Fc.ST, null, false, false);
        assertNull(Iec61850Values.time(t)); // yazilmamis = 1970
        assertNull(Iec61850Values.time(null));
        Instant x = Instant.parse("2026-11-20T10:00:00.125Z");
        t.setInstant(x);
        assertEquals(x, Iec61850Values.time(t));
        t.setInstant(x, true, true, false, 10); // saat arizasi
        assertNull(Iec61850Values.time(t));
        t.setInstant(x, true, false, true, 10); // senkronsuz ama gecerli
        assertEquals(x, Iec61850Values.time(t));
    }
}

package com.enerjione.sagateway.iec104;

import static com.enerjione.sagateway.iec104.TestSlave.asdu;
import static com.enerjione.sagateway.iec104.TestSlave.concat;
import static com.enerjione.sagateway.iec104.TestSlave.cp56;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class CodecTest {

    private final AsduCodec codec = AsduCodec.standard(ZoneOffset.UTC);

    private Asdu decode(byte[] a) throws Exception {
        return codec.decode(a, 0, a.length);
    }

    private InfoObject one(byte[] a) throws Exception {
        Asdu d = decode(a);
        assertEquals(1, d.objects().size());
        return d.objects().get(0);
    }

    @Test
    void tekNokta_degerVeKalite() throws Exception {
        InfoObject o = one(asdu(1, 20, 1, 1001, (byte) 0x01));
        assertEquals(1001, o.ioa());
        assertEquals(1.0, o.value());
        assertTrue(o.good());
        InfoObject iv = one(asdu(1, 3, 1, 1001, (byte) 0x81));
        assertFalse(iv.good());
        assertEquals("IV", iv.flagText());
    }

    @Test
    void ciftNokta_zamanli() throws Exception {
        Instant t = Instant.parse("2026-11-20T08:15:30.250Z");
        InfoObject o = one(asdu(31, 3, 1, 2001, concat(new byte[] {0x02}, cp56(t))));
        assertEquals(2.0, o.value()); // kapali
        assertEquals(t, o.time());
        assertTrue(o.good());
    }

    @Test
    void cp56_yerelSaatDilimi() {
        byte[] b = new byte[7];
        Instant t = Instant.parse("2026-07-01T09:00:00Z");
        ZoneId ist = ZoneId.of("Europe/Istanbul");
        Cp56Time2a.encode(t, ist, b, 0);
        assertEquals(12, b[3] & 0x1F); // yerel saat 12:00 (UTC+3)
        assertEquals(t, Cp56Time2a.decode(b, 0, ist).instant());
        // Ayni baytlari UTC sanmak 3 saat kaydirir: ayar neden gerekli.
        assertEquals(t.plusSeconds(3 * 3600), Cp56Time2a.decode(b, 0, ZoneOffset.UTC).instant());
    }

    @Test
    void cp56_gecersizBit_veBozukTarih() {
        byte[] b = cp56(Instant.parse("2026-01-02T03:04:05Z"));
        b[2] |= (byte) 0x80;
        assertTrue(Cp56Time2a.decode(b, 0, ZoneOffset.UTC).invalid());
        byte[] bozuk = new byte[7]; // ay 0, gun 0
        Cp56Time2a.Value v = Cp56Time2a.decode(bozuk, 0, ZoneOffset.UTC);
        assertNull(v.instant());
        assertTrue(v.invalid());
    }

    @Test
    void zamanEtiketiGecersizse_degerYineGelir() throws Exception {
        byte[] t = cp56(Instant.parse("2026-01-02T03:04:05Z"));
        t[2] |= (byte) 0x80;
        InfoObject o = one(asdu(30, 3, 1, 5, concat(new byte[] {0x01}, t)));
        assertEquals(1.0, o.value());
        assertTrue(o.good()); // deger kalitesi ayri
        assertTrue((o.flags() & InfoObject.TIME_INVALID) != 0);
    }

    @Test
    void olcumTipleri() throws Exception {
        // M_ME_NC_1 float 230.5, QDS 0
        int bits = Float.floatToIntBits(230.5f);
        InfoObject f = one(asdu(13, 3, 1, 3001,
                (byte) bits, (byte) (bits >> 8), (byte) (bits >> 16), (byte) (bits >> 24), (byte) 0));
        assertEquals(230.5, f.value(), 1e-6);
        // M_ME_NB_1 olceklenmis -1234, OV
        InfoObject sc = one(asdu(11, 3, 1, 3002, (byte) 0x2E, (byte) 0xFB, (byte) 0x01));
        assertEquals(-1234.0, sc.value());
        assertFalse(sc.good());
        assertEquals("OV", sc.flagText());
        // M_ME_NA_1 normalize -0.5 (0xC000)
        InfoObject nv = one(asdu(9, 3, 1, 3003, (byte) 0x00, (byte) 0xC0, (byte) 0x00));
        assertEquals(-0.5, nv.value(), 1e-9);
        // M_ME_ND_1 kalitesiz
        InfoObject nd = one(asdu(21, 1, 1, 3004, (byte) 0x00, (byte) 0x40));
        assertEquals(0.5, nd.value(), 1e-9);
        assertTrue(nd.good());
    }

    @Test
    void kademe_isaretVeGecis() throws Exception {
        InfoObject k = one(asdu(5, 3, 1, 4001, (byte) (0x80 | 0x7E), (byte) 0)); // -2, gecis
        assertEquals(-2.0, k.value());
        assertTrue((k.flags() & InfoObject.TRANSIENT) != 0);
        assertTrue(k.good());
    }

    @Test
    void sayac_ve_bitDizisi() throws Exception {
        InfoObject c = one(asdu(15, 37, 1, 5001, (byte) 0x10, (byte) 0x27, (byte) 0, (byte) 0, (byte) 0x23));
        assertEquals(10000.0, c.value());
        assertTrue((c.flags() & InfoObject.CARRY) != 0);
        assertTrue(c.good());
        InfoObject bs = one(asdu(7, 3, 1, 5002, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0));
        assertEquals(4294967295.0, bs.value());
    }

    @Test
    void siraliNesneler_SQ1() throws Exception {
        // M_SP_NA_1, SQ=1, N=3, IOA 100'den baslar
        byte[] a = {1, (byte) 0x83, 20, 0, 7, 0, 100, 0, 0, 0x00, 0x01, 0x01};
        Asdu d = decode(a);
        assertEquals(7, d.commonAddress());
        assertEquals(3, d.objects().size());
        assertEquals(102, d.objects().get(2).ioa());
        assertEquals(1.0, d.objects().get(2).value());
        assertEquals("interrogation", d.causeName());
    }

    @Test
    void kisaAsdu_reddedilir_bilinmeyenTip_bosDoner() throws Exception {
        byte[] kisa = {13, 1, 3, 0, 1, 0, 1, 0, 0, 0, 0};
        assertThrows(AsduCodec.MalformedAsduException.class, () -> decode(kisa));
        Asdu bilinmeyen = decode(asdu(38, 3, 1, 1, new byte[10]));
        assertNull(bilinmeyen.type());
        assertTrue(bilinmeyen.objects().isEmpty());
    }

    @Test
    void negatifVeTestBitleri() throws Exception {
        byte[] a = asdu(100, 7, 1, 0, (byte) 20);
        a[2] = (byte) (7 | 0x40 | 0x80);
        Asdu d = decode(a);
        assertTrue(d.negative());
        assertTrue(d.test());
        assertEquals(7, d.cause());
    }

    @Test
    void genelSorguKomutu() {
        byte[] gi = codec.interrogation(6, 0, 0x1234, 20);
        assertArrayEquals(new byte[] {100, 1, 6, 0, 0x34, 0x12, 0, 0, 0, 20}, gi);
    }

    @Test
    void apci_kurVeCoz_siraTasmasi() {
        byte[] i = Apci.i(32767, 5, new byte[] {1, 2});
        Apci.Frame f = Apci.parse(i, 0, i[1] & 0xFF);
        assertEquals(Apci.Kind.I, f.kind());
        assertEquals(32767, f.sendSeq());
        assertEquals(5, f.recvSeq());
        byte[] s = Apci.s(300);
        assertEquals(300, Apci.parse(s, 0, 4).recvSeq());
        assertEquals(Apci.Kind.U, Apci.parse(Apci.u(Apci.TESTFR_ACT), 0, 4).kind());
        assertEquals(2, Apci.distance(32767, 1));
        assertEquals(0, Apci.distance(9, 9));
    }

    @Test
    void ayarlar_standartKurallari() {
        assertThrows(IllegalArgumentException.class,
                () -> new Iec104Settings("h", 2404, null, 1, 0, 12, 13, 30, 15, 10, 20, 0, null));
        assertThrows(IllegalArgumentException.class,
                () -> new Iec104Settings("h", 2404, null, 1, 0, 12, 8, 30, 10, 10, 20, 0, null));
        assertEquals(ZoneOffset.UTC.normalized(),
                new Iec104Settings("h", 2404, null, 1, 0, 12, 8, 30, 15, 10, 20, 0, null).zone().normalized());
    }
}

package com.enerjione.sagateway.iec61850;

import static com.enerjione.sagateway.iec61850.TestIed.LD;
import static com.enerjione.sagateway.iec61850.TestIed.T0;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.beanit.iec61850bean.BdaDoubleBitPos.DoubleBitPos;
import com.beanit.iec61850bean.BdaQuality.Validity;
import com.enerjione.sagateway.grid.GridClient.ValueItem;
import com.enerjione.sagateway.iec61850.Iec61850Ied.PointRef;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** Surec ici iec61850bean sunucusu -> Iec61850Ied -> deger listesi (Grid yerine). */
@Timeout(60)
class Iec61850IedTest {

    static final PointRef POS = new PointRef(1, "Q0.Pos", "dp", LD + "/XCBR1.Pos.stVal", "ST");
    static final PointRef IA = new PointRef(2, "IL1", "mv", LD + "/MMXU1.A.phsA.cVal.mag.f", "MX");
    static final PointRef IND = new PointRef(3, "Ind1", "sp", LD + "/GGIO1.Ind1.stVal", "ST");
    static final PointRef TOTW = new PointRef(4, "P", "mv", LD + "/MMXU1.TotW.mag.f", "MX");
    static final PointRef WH = new PointRef(5, "Wh", "it", LD + "/MMTR1.TotWh.actVal", "ST");
    static final PointRef BEH = new PointRef(6, "Beh", "st", LD + "/XCBR1.Beh.stVal", "ST");
    static final PointRef UNKNOWN = new PointRef(7, "Yok", "sp", LD + "/GGIO1.Ind9.stVal", "ST");
    static final PointRef MISMATCH = new PointRef(8, "P-sp", "sp", LD + "/MMXU1.TotW.mag.f", "MX");
    static final PointRef TXT = new PointRef(9, "Metin", "txt", LD + "/GGIO1.Ind1.stVal", "ST");
    static final PointRef NOT_LEAF = new PointRef(10, "PosDO", "dp", LD + "/XCBR1.Pos", "ST");
    static final List<PointRef> ALL = List.of(POS, IA, IND, TOTW, WH, BEH, UNKNOWN, MISMATCH, TXT, NOT_LEAF);

    static final RcbRef URCB = RcbRef.parse(LD + "/LLN0$RP$urcbMeas"); // modelde urcbMeas01 / 02
    static final RcbRef BRCB = RcbRef.parse(LD + "/LLN0.BR.brcbStat");

    private TestIed ied;
    private Iec61850Ied handler;
    private final List<ValueItem> got = new CopyOnWriteArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        ied = new TestIed();
    }

    @AfterEach
    void tearDown() {
        if (handler != null) handler.close();
        ied.close();
    }

    private Iec61850Client.Settings base() {
        return Iec61850Client.Settings.of("127.0.0.1", ied.port()).withTimeouts(3000, 30_000).withGiOnConnect(false);
    }

    private Iec61850Ied start(Iec61850Client.Settings s, List<PointRef> points) {
        handler = new Iec61850Ied(1, "REL61850", "fp", s, points, got::add);
        handler.start();
        return handler;
    }

    private List<ValueItem> of(long id) {
        return got.stream().filter(v -> v.pointId() == id).toList();
    }

    private List<ValueItem> of(long id, String cause) {
        return got.stream().filter(v -> v.pointId() == id && cause.equals(v.cause())).toList();
    }

    private Optional<ValueItem> last(long id, Predicate<ValueItem> p) {
        List<ValueItem> l = of(id).stream().filter(p).toList();
        return l.isEmpty() ? Optional.empty() : Optional.of(l.get(l.size() - 1));
    }

    private static void waitFor(BooleanSupplier c, long ms) throws InterruptedException {
        long end = System.currentTimeMillis() + ms;
        while (System.currentTimeMillis() < end) {
            if (c.getAsBoolean()) return;
            Thread.sleep(20);
        }
        assertTrue(c.getAsBoolean(), "kosul " + ms + " ms icinde saglanmadi");
    }

    private void waitInitial() throws InterruptedException {
        waitFor(() -> !of(POS.id(), "interrogation").isEmpty() && !of(IA.id(), "interrogation").isEmpty()
                && !of(IND.id(), "interrogation").isEmpty() && !of(WH.id(), "interrogation").isEmpty()
                && !of(BEH.id(), "interrogation").isEmpty() && !of(TOTW.id(), "interrogation").isEmpty(), 15_000);
    }

    @Test
    void ilk_okuma_tum_tipleri_cevirir() throws Exception {
        start(base(), ALL);
        waitInitial();

        ValueItem pos = of(POS.id(), "interrogation").get(0);
        assertEquals(2.0, pos.value()); // Dbpos ON = kapali = 2
        assertEquals("good", pos.quality());
        assertEquals(T0.toString(), pos.ts());

        ValueItem ia = of(IA.id(), "interrogation").get(0);
        assertEquals(12.5, ia.value());
        assertEquals("good", ia.quality());
        assertEquals(T0.toString(), ia.ts()); // phsA.t (SDO'nun kardes t'si)

        assertEquals(1.0, of(IND.id()).get(0).value());
        assertEquals(1000.5, of(TOTW.id()).get(0).value());
        assertNull(of(TOTW.id()).get(0).ts()); // t hic yazilmadi (1970) -> null
        assertEquals(123456.0, of(WH.id()).get(0).value());
        assertEquals(1.0, of(BEH.id()).get(0).value()); // ENUM "on" = 1

        // Bilinmeyen ref: bir kez gecersiz; tip uyusmazligi, txt ve yaprak olmayan ref hic gonderilmez.
        waitFor(() -> !of(UNKNOWN.id()).isEmpty(), 5000);
        Thread.sleep(500);
        List<ValueItem> unk = of(UNKNOWN.id());
        assertEquals(1, unk.size());
        assertNull(unk.get(0).value());
        assertEquals("invalid", unk.get(0).quality());
        assertTrue(of(MISMATCH.id()).isEmpty());
        assertTrue(of(TXT.id()).isEmpty());
        assertTrue(of(NOT_LEAF.id()).isEmpty());

        assertEquals(9, handler.pointCount()); // 10 nokta, txt sayilmaz
        assertTrue(handler.status().connected());
        assertEquals("iec61850", handler.protocol());
    }

    @Test
    void rapor_ile_degisim_spontaneous_gelir() throws Exception {
        start(base().withRcbs(List.of(URCB)).withGiOnConnect(true), List.of(POS, IA, IND));
        waitInitial2();
        // urcbMeas yok, urcbMeas01 kullanildi (numarali ornek).
        waitFor(() -> handler.status().reportsEnabled() == 1, 5000);
        // GI raporu geldi (degisim olmadan); sebebi GI oldugu icin "interrogation".
        // Yoklama kapali: ikinci interrogation degeri yalniz GI raporundan gelebilir.
        waitFor(() -> handler.status().messages() >= 1, 5000);
        waitFor(() -> of(POS.id(), "interrogation").size() >= 2 && of(IA.id(), "interrogation").size() >= 2, 5000);
        assertTrue(of(POS.id(), "spontaneous").isEmpty());

        Instant t1 = Instant.parse("2026-11-21T08:15:30.500Z");
        ied.setPos(DoubleBitPos.OFF, Validity.GOOD, t1);
        waitFor(() -> last(POS.id(), v -> "spontaneous".equals(v.cause())).isPresent(), 5000);
        ValueItem s = last(POS.id(), v -> "spontaneous".equals(v.cause())).orElseThrow();
        assertEquals(1.0, s.value()); // OFF = acik = 1
        assertEquals("good", s.quality());
        assertEquals(t1.toString(), s.ts());
    }

    private void waitInitial2() throws InterruptedException {
        waitFor(() -> !of(POS.id(), "interrogation").isEmpty() && !of(IA.id(), "interrogation").isEmpty()
                && !of(IND.id(), "interrogation").isEmpty(), 15_000);
    }

    @Test
    void gecersiz_kalite_raporla_iletilir() throws Exception {
        start(base().withRcbs(List.of(URCB)), List.of(POS, IA, IND));
        waitInitial2();
        waitFor(() -> handler.status().reportsEnabled() == 1, 5000);

        Instant t2 = Instant.parse("2026-11-21T09:00:00Z");
        ied.setPhsA(99.25f, Validity.INVALID, t2);
        waitFor(() -> last(IA.id(), v -> "spontaneous".equals(v.cause())).isPresent(), 5000);
        ValueItem v = last(IA.id(), x -> "spontaneous".equals(x.cause())).orElseThrow();
        assertEquals(99.25, v.value()); // deger korunur, kalite gecersiz
        assertEquals("invalid", v.quality());
        assertEquals(t2.toString(), v.ts());

        // Sorgulanabilir (questionable) da gecersiz sayilir.
        ied.setPos(DoubleBitPos.ON, Validity.QUESTIONABLE, t2);
        waitFor(() -> last(POS.id(), x -> "spontaneous".equals(x.cause())).isPresent(), 5000);
        assertEquals("invalid", last(POS.id(), x -> "spontaneous".equals(x.cause())).orElseThrow().quality());

        // NaN olcum: deger null + gecersiz.
        got.clear();
        ied.setPhsA(Float.NaN, Validity.GOOD, t2);
        waitFor(() -> last(IA.id(), x -> "spontaneous".equals(x.cause())).isPresent(), 5000);
        ValueItem nan = last(IA.id(), x -> "spontaneous".equals(x.cause())).orElseThrow();
        assertNull(nan.value());
        assertEquals("invalid", nan.quality());
    }

    @Test
    void rcb_yoksa_yoklama_ile_periyodik_okunur() throws Exception {
        // Bulunamayan RCB: etkin rapor yok, yoklama yine calisir.
        RcbRef missing = RcbRef.parse(LD + "/LLN0.BR.yokBlok");
        start(base().withRcbs(List.of(missing)).withPollMs(300), List.of(POS, IA));
        waitInitial2NoInd();
        assertEquals(0, handler.status().reportsEnabled());

        ied.setPhsA(20.0f, Validity.GOOD, Instant.parse("2026-11-21T10:00:00Z"));
        waitFor(() -> last(IA.id(), v -> "periodic".equals(v.cause()) && Double.valueOf(20.0).equals(v.value()))
                .isPresent(), 5000);
        assertTrue(of(IA.id(), "spontaneous").isEmpty()); // rapor yolu yok
    }

    private void waitInitial2NoInd() throws InterruptedException {
        waitFor(() -> !of(POS.id(), "interrogation").isEmpty() && !of(IA.id(), "interrogation").isEmpty(), 15_000);
    }

    @Test
    void sunucu_durursa_comm_lost_sonra_yeniden_baglanir() throws Exception {
        start(base().withRcbs(List.of(URCB)), List.of(POS, IA, UNKNOWN));
        waitInitial2NoInd();
        waitFor(() -> !of(UNKNOWN.id()).isEmpty(), 5000);

        ied.stop();
        waitFor(() -> of(POS.id(), "comm_lost").size() == 1 && of(IA.id(), "comm_lost").size() == 1
                && of(UNKNOWN.id(), "comm_lost").size() == 1, 8000);
        ValueItem lost = of(POS.id(), "comm_lost").get(0);
        assertNull(lost.value());
        assertEquals("invalid", lost.quality());
        assertFalse(handler.status().connected());
        // Basarisiz yeniden denemeler tekrar tekrar gecersiz yollamaz.
        Thread.sleep(1500);
        assertEquals(1, of(POS.id(), "comm_lost").size());

        ied.start();
        waitFor(() -> of(POS.id(), "interrogation").size() >= 2 && of(IA.id(), "interrogation").size() >= 2, 20_000);
        waitFor(() -> handler.status().connected() && handler.status().reportsEnabled() == 1, 5000);
        assertTrue(handler.status().reconnects() >= 1);
        // Bilinmeyen ref yeniden baglantida tekrar "gecersiz" gonderilmez.
        assertEquals(1, of(UNKNOWN.id(), "interrogation").size());

        // Yeniden kurulan raporlar calisir.
        Instant t3 = Instant.parse("2026-11-21T11:00:00Z");
        ied.setPos(DoubleBitPos.INTERMEDIATE_STATE, Validity.GOOD, t3);
        waitFor(() -> last(POS.id(), v -> "spontaneous".equals(v.cause())).isPresent(), 5000);
        assertEquals(0.0, last(POS.id(), v -> "spontaneous".equals(v.cause())).orElseThrow().value());
    }

    @Test
    void tamponlu_ve_tamponsuz_rcb_birlikte_etkinlesir() throws Exception {
        start(base().withRcbs(List.of(BRCB, URCB)), List.of(POS));
        waitFor(() -> !of(POS.id(), "interrogation").isEmpty(), 15_000);
        waitFor(() -> handler.status().reportsEnabled() == 2, 5000);
    }

    @Test
    void ikinci_istemci_siradaki_urcb_ornegini_alir() throws Exception {
        start(base().withRcbs(List.of(URCB)), List.of(POS));
        waitFor(() -> handler.status().reportsEnabled() == 1, 15_000);
        List<ValueItem> other = new CopyOnWriteArrayList<>();
        try (Iec61850Ied second = new Iec61850Ied(2, "REL61850-2", "fp2", base().withRcbs(List.of(URCB)),
                List.of(POS), other::add)) {
            second.start();
            waitFor(() -> second.status().reportsEnabled() == 1, 15_000); // urcbMeas02
            ied.setPos(DoubleBitPos.OFF, Validity.GOOD, Instant.parse("2026-11-21T12:00:00Z"));
            waitFor(() -> other.stream().anyMatch(v -> "spontaneous".equals(v.cause())), 5000);
            waitFor(() -> last(POS.id(), v -> "spontaneous".equals(v.cause())).isPresent(), 5000);
        }
    }

    @Test
    void kapatildiktan_sonra_deger_yazilmaz() throws Exception {
        start(base().withPollMs(100), List.of(POS));
        waitFor(() -> !of(POS.id(), "periodic").isEmpty(), 15_000);
        handler.close();
        int n = got.size();
        Thread.sleep(600);
        assertEquals(n, got.size());
        assertTrue(of(POS.id(), "comm_lost").isEmpty()); // bilerek kapatma hat kopmasi degil
    }

    @Test
    void yari_acik_hat_canlilik_okumasiyla_fark_edilir() throws Exception {
        try (FreezableProxy proxy = new FreezableProxy(ied.port())) {
            // Yoklama yok, rapor yok: kopuklugu yalniz canlilik okumasi fark edebilir.
            start(Iec61850Client.Settings.of("127.0.0.1", proxy.port()).withTimeouts(1000, 300)
                    .withGiOnConnect(false), List.of(POS));
            waitFor(() -> !of(POS.id(), "interrogation").isEmpty(), 15_000);
            // Saglam ama bos hat: canlilik okumalari baglantiyi koparmaz.
            Thread.sleep(1500);
            assertTrue(handler.status().connected());
            assertTrue(of(POS.id(), "comm_lost").isEmpty());

            // Hat sessizce olur (FIN/RST yok): okuma yanitsiz kalir -> comm_lost.
            proxy.frozen = true;
            waitFor(() -> of(POS.id(), "comm_lost").size() == 1, 8000);
            assertFalse(handler.status().connected());

            proxy.frozen = false;
            waitFor(() -> of(POS.id(), "interrogation").size() >= 2, 25_000);
            assertEquals(1, of(POS.id(), "comm_lost").size());
        }
    }

    /** Baytlari ileten, istenince sessizce yutan (yari acik hat benzetimi) TCP vekili. */
    static final class FreezableProxy implements AutoCloseable {
        final java.net.ServerSocket server;
        final int target;
        volatile boolean frozen;
        private final List<java.net.Socket> sockets = new CopyOnWriteArrayList<>();

        FreezableProxy(int target) throws java.io.IOException {
            this.target = target;
            this.server = new java.net.ServerSocket(0, 5, java.net.InetAddress.getByName("127.0.0.1"));
            Thread t = new Thread(this::acceptLoop, "test-proxy");
            t.setDaemon(true);
            t.start();
        }

        int port() {
            return server.getLocalPort();
        }

        private void acceptLoop() {
            while (!server.isClosed()) {
                try {
                    java.net.Socket c = server.accept();
                    java.net.Socket u = new java.net.Socket("127.0.0.1", target);
                    sockets.add(c);
                    sockets.add(u);
                    pump(c, u);
                    pump(u, c);
                } catch (java.io.IOException e) {
                    return;
                }
            }
        }

        private void pump(java.net.Socket from, java.net.Socket to) {
            Thread t = new Thread(() -> {
                byte[] buf = new byte[8192];
                try (from; to) {
                    int n;
                    while ((n = from.getInputStream().read(buf)) >= 0) {
                        if (!frozen) to.getOutputStream().write(buf, 0, n);
                    }
                } catch (java.io.IOException e) {
                    // kapandi
                }
            }, "test-proxy-pump");
            t.setDaemon(true);
            t.start();
        }

        @Override
        public void close() throws java.io.IOException {
            server.close();
            for (java.net.Socket s : sockets) s.close();
        }
    }
}

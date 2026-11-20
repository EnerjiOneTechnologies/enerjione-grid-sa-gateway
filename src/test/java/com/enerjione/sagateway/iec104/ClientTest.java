package com.enerjione.sagateway.iec104;

import static com.enerjione.sagateway.iec104.TestSlave.asdu;
import static com.enerjione.sagateway.iec104.TestSlave.concat;
import static com.enerjione.sagateway.iec104.TestSlave.cp56;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** Istemci ile test istasyonu arasinda gercek TCP uzerinden protokol testleri. */
@Timeout(60)
class ClientTest {

    private TestSlave slave;
    private Iec104Client client;
    private final LinkedBlockingQueue<Asdu> received = new LinkedBlockingQueue<>();
    private final AtomicInteger connected = new AtomicInteger();
    private final AtomicInteger disconnected = new AtomicInteger();
    private final AtomicInteger giDone = new AtomicInteger();

    private final Iec104Client.Listener listener = new Iec104Client.Listener() {
        @Override
        public void onConnected(String host) {
            connected.incrementAndGet();
        }

        @Override
        public void onDisconnected(String reason) {
            disconnected.incrementAndGet();
        }

        @Override
        public void onInterrogationDone() {
            giDone.incrementAndGet();
        }

        @Override
        public void onAsdu(Asdu a) {
            received.add(a);
        }
    };

    @BeforeEach
    void setUp() throws Exception {
        slave = new TestSlave(7);
        slave.giData.add(asdu(1, 20, 7, 1001, (byte) 0x01));
        slave.giData.add(asdu(3, 20, 7, 2001, (byte) 0x01));
    }

    @AfterEach
    void tearDown() throws Exception {
        if (client != null) client.close();
        slave.close();
    }

    private Iec104Settings settings(int w, int t1, int t2, int t3) {
        return new Iec104Settings("127.0.0.1", slave.port(), null, 7, 0, 12, w, 5, t1, t2, t3, 0, ZoneOffset.UTC);
    }

    private void start(Iec104Settings s) {
        client = new Iec104Client("test", s, listener);
        client.start();
    }

    private Asdu next() throws InterruptedException {
        Asdu a = received.poll(5, TimeUnit.SECONDS);
        assertNotNull(a, "ASDU gelmedi");
        return a;
    }

    private static void waitFor(java.util.function.BooleanSupplier c, long ms) throws InterruptedException {
        long end = System.currentTimeMillis() + ms;
        while (System.currentTimeMillis() < end) {
            if (c.getAsBoolean()) return;
            Thread.sleep(20);
        }
        assertTrue(c.getAsBoolean(), "kosul " + ms + " ms icinde saglanmadi");
    }

    @Test
    void baglanir_genelSorgu_veKendiligindenDegisim() throws Exception {
        start(settings(8, 15, 10, 20));
        Asdu sp = next();
        assertEquals("interrogation", sp.causeName());
        assertEquals(1001, sp.objects().get(0).ioa());
        Asdu dp = next();
        assertEquals(1.0, dp.objects().get(0).value()); // acik
        waitFor(() -> giDone.get() == 1, 3000);
        assertEquals(1, connected.get());
        assertEquals(1, slave.interrogations.get());

        Instant t = Instant.parse("2026-11-20T10:00:00.125Z");
        slave.send(asdu(31, 3, 7, 2001, concat(new byte[] {0x02}, cp56(t))));
        Asdu spont = next();
        assertEquals(TypeId.M_DP_TB_1, spont.type());
        assertEquals("spontaneous", spont.causeName());
        assertEquals(2.0, spont.objects().get(0).value());
        assertEquals(t, spont.objects().get(0).time());
        assertTrue(client.status().connected());
    }

    @Test
    void w_cerceveden_sonra_S_onayi() throws Exception {
        start(settings(4, 15, 10, 20));
        waitFor(() -> giDone.get() == 1, 5000);
        // GI: ACT_CON + 2 veri + ACT_TERM = 4 cerceve -> w=4 dolunca S(4)
        Integer nr = slave.nextSFrame(3000);
        assertNotNull(nr, "S cercevesi gelmedi");
        assertEquals(4, nr);
        for (int i = 0; i < 4; i++) slave.send(asdu(1, 3, 7, 1001, (byte) (i & 1)));
        assertEquals(8, slave.nextSFrame(3000));
    }

    @Test
    void t2_doldugunda_bekleyenler_onaylanir() throws Exception {
        start(settings(8, 3, 1, 20));
        waitFor(() -> giDone.get() == 1, 5000);
        Integer nr = slave.nextSFrame(3000); // 4 cerceve < w=8; t2=1 sn sonra S
        assertEquals(4, nr);
    }

    @Test
    void t3_sessizlikte_TESTFR_gonderir() throws Exception {
        start(settings(8, 3, 1, 1));
        waitFor(() -> slave.testfrReceived.get() >= 2, 6000);
        assertEquals(1, connected.get());
        assertEquals(0, disconnected.get());
    }

    @Test
    void TESTFR_onaylanmazsa_t1_ile_kapatir_ve_yeniden_baglanir() throws Exception {
        slave.answerTestfr = false;
        start(settings(8, 2, 1, 1));
        waitFor(() -> disconnected.get() >= 1, 8000);
        waitFor(() -> slave.connections.get() >= 2, 8000);
        waitFor(() -> slave.interrogations.get() >= 2, 8000); // yeni baglantida yeniden GI
    }

    @Test
    void STARTDT_onaylanmazsa_kapatir() throws Exception {
        slave.answerStartdt = false;
        AtomicInteger failed = new AtomicInteger();
        client = new Iec104Client("test", settings(8, 2, 1, 20), new Iec104Client.Listener() {
            @Override
            public void onConnectFailed(String host, String reason) {
                if (reason.contains("STARTDT")) failed.incrementAndGet();
            }

            @Override
            public void onAsdu(Asdu a) {
            }
        });
        client.start();
        waitFor(() -> failed.get() >= 1, 6000);
    }

    @Test
    void hat_koparsa_yeniden_baglanir_ve_tekrar_sorgular() throws Exception {
        start(settings(8, 15, 10, 20));
        waitFor(() -> giDone.get() == 1, 5000);
        slave.dropConnection();
        waitFor(() -> disconnected.get() == 1, 5000);
        assertTrue(!client.status().connected());
        waitFor(() -> giDone.get() == 2, 8000);
        assertEquals(2, connected.get());
    }

    @Test
    void sira_hatasinda_baglantiyi_keser() throws Exception {
        slave.firstSendSeqOverride = 5;
        start(settings(8, 15, 10, 20));
        waitFor(() -> slave.connections.get() >= 2, 8000);
        assertEquals(0, received.size()); // hatali siradaki veri islenmez
    }

    @Test
    void istasyon_yeniden_baslarsa_genel_sorgu_yeniler() throws Exception {
        start(settings(8, 15, 10, 20));
        waitFor(() -> giDone.get() == 1, 5000);
        slave.send(asdu(70, 4, 7, 0, (byte) 0)); // M_EI_NA_1
        waitFor(() -> slave.interrogations.get() == 2, 3000);
    }

    @Test
    void yedek_adrese_gecer() throws Exception {
        // Birincil 127.0.0.2'de dinleyen yok; yedek 127.0.0.1.
        Iec104Settings s = new Iec104Settings("127.0.0.2", slave.port(), "127.0.0.1", 7, 0, 12, 8, 2, 15, 10, 20,
                0, ZoneOffset.UTC);
        start(s);
        waitFor(() -> giDone.get() == 1, 10000);
        assertEquals("127.0.0.1", client.status().host());
    }

    @Test
    void periyodik_genel_sorgu() throws Exception {
        start(settings(8, 15, 10, 20).withGiInterval(1));
        waitFor(() -> slave.interrogations.get() >= 3, 6000);
    }
}

package com.enerjione.sagateway.gateway;

import static com.enerjione.sagateway.iec104.TestSlave.asdu;
import static com.enerjione.sagateway.iec104.TestSlave.concat;
import static com.enerjione.sagateway.iec104.TestSlave.cp56;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.beanit.iec61850bean.BdaDoubleBitPos.DoubleBitPos;
import com.beanit.iec61850bean.BdaQuality.Validity;
import com.enerjione.sagateway.grid.GridClient;
import com.enerjione.sagateway.grid.GridClient.ValueItem;
import com.enerjione.sagateway.iec104.TestSlave;
import com.enerjione.sagateway.iec104.TypeId;
import com.enerjione.sagateway.iec61850.TestIed;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** Uctan uca: test istasyonu -> gateway -> sahte Grid. */
@Timeout(60)
class GatewayTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String TOKEN = "deneme-belirteci";

    private TestSlave slave;
    private HttpServer grid;
    private SaGateway gw;
    private final List<JsonNode> posted = new CopyOnWriteArrayList<>();
    private final AtomicInteger failNextPosts = new AtomicInteger();
    private final AtomicInteger unauthorized = new AtomicInteger();
    private final List<String> upgradeHeaders = new CopyOnWriteArrayList<>();
    private volatile String config;
    /** /config yaniti: 200 disinda bu durum kodu doner (404 modul kapali, 403 lisans). */
    private volatile int configStatus = 200;
    /** /jobs yaniti: 200 disinda bu durum kodu doner (404 eski Grid). */
    private volatile int jobsStatus = 200;
    /** Sirayla verilecek isler (yoklama basina en fazla bir). */
    private final Queue<String> jobQueue = new ConcurrentLinkedQueue<>();
    private final AtomicInteger jobPolls = new AtomicInteger();
    private final List<String> jobResultPaths = new CopyOnWriteArrayList<>();
    private final List<JsonNode> jobResults = new CopyOnWriteArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        slave = new TestSlave(7);
        slave.giData.add(asdu(1, 20, 7, 1001, (byte) 0x01)); // sp -> nokta 11
        slave.giData.add(asdu(3, 20, 7, 2001, (byte) 0x01)); // dp -> nokta 12
        slave.giData.add(asdu(1, 20, 7, 9999, (byte) 0x01)); // eslenmemis
        config = configJson(slave.port(), 1);

        grid = HttpServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0);
        grid.createContext("/api/v1/sa/gateway/", ex -> {
            String up = ex.getRequestHeaders().getFirst("Upgrade");
            if (up != null) upgradeHeaders.add(up);
            String body;
            int status = 200;
            if (!TOKEN.equals(ex.getRequestHeaders().getFirst("X-SA-Gateway-Token"))) {
                unauthorized.incrementAndGet();
                status = 401;
                body = "{\"detail\":\"Invalid gateway token\"}";
            } else if (ex.getRequestURI().getPath().endsWith("/config")) {
                if (configStatus != 200) {
                    status = configStatus;
                    body = "{\"detail\":\"SA module disabled\"}";
                } else {
                    body = config;
                }
            } else if (ex.getRequestURI().getPath().endsWith("/jobs")) {
                jobPolls.incrementAndGet();
                if (jobsStatus != 200) {
                    status = jobsStatus;
                    body = "{\"detail\":\"Not Found\"}";
                } else {
                    String j = jobQueue.poll();
                    body = "{\"jobs\":[" + (j == null ? "" : j) + "]}";
                }
            } else if (ex.getRequestURI().getPath().endsWith("/result")) {
                jobResults.add(JSON.readTree(ex.getRequestBody()));
                jobResultPaths.add(ex.getRequestURI().getPath());
                body = "{\"accepted\":true}";
            } else if (failNextPosts.getAndUpdate(n -> Math.max(0, n - 1)) > 0) {
                ex.getRequestBody().readAllBytes();
                status = 503;
                body = "{\"detail\":\"gecici\"}";
            } else {
                JsonNode req = JSON.readTree(ex.getRequestBody());
                posted.add(req);
                body = "{\"accepted\":" + req.path("values").size() + ",\"rejected\":[]}";
            }
            byte[] b = body.getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(status, b.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(b);
            }
        });
        grid.start();
    }

    @AfterEach
    void tearDown() throws Exception {
        if (gw != null) gw.close();
        grid.stop(0);
        slave.close();
    }

    private static String configJson(int port, int version) {
        return """
                {"config_version":"v%d","ieds":[
                  {"id":1,"code":"REL1","protocol":"iec104",
                   "connection":{"host":"127.0.0.1","port":%d,"common_address":7,"originator_address":0,
                                 "k":12,"w":8,"t0":5,"t1":15,"t2":10,"t3":20,"gi_interval_sec":0,"redundant_host":null},
                   "points":[
                     {"id":11,"code":"M1/REL1/K1.Alarm","point_type":"sp","address":{"type_id":1,"ioa":1001}},
                     {"id":12,"code":"M1/REL1/Q0.Pos","point_type":"dp","address":{"type_id":31,"ioa":2001}},
                     {"id":13,"code":"M1/REL1/IL1","point_type":"mv","address":{"type_id":13,"ioa":1001}}
                   ]},
                  {"id":2,"code":"REL2","protocol":"modbus","connection":{"host":"127.0.0.1"},"points":[]}
                ]}""".formatted(version, port);
    }

    private SaGateway startGateway(String token) throws Exception {
        String base = "http://127.0.0.1:" + grid.getAddress().getPort() + "/api/v1";
        gw = new SaGateway(new GridClient(base, token),
                new SaGateway.Options(1, 100, 1000, 100_000, 0, ZoneOffset.UTC));
        gw.jobIntervals(200, 60_000);
        gw.start();
        return gw;
    }

    private List<JsonNode> values() {
        return posted.stream().flatMap(r -> {
            List<JsonNode> l = new java.util.ArrayList<>();
            r.path("values").forEach(l::add);
            return l.stream();
        }).toList();
    }

    private static void waitFor(BooleanSupplier c, long ms) throws InterruptedException {
        long end = System.currentTimeMillis() + ms;
        while (System.currentTimeMillis() < end) {
            if (c.getAsBoolean()) return;
            Thread.sleep(20);
        }
        assertTrue(c.getAsBoolean(), "kosul " + ms + " ms icinde saglanmadi");
    }

    @Test
    void genelSorgu_ve_kendiligindenDegisim_Gride_ulasir() throws Exception {
        startGateway(TOKEN);
        waitFor(() -> values().size() >= 2, 10000);
        List<JsonNode> v = values();
        assertEquals(11, v.get(0).path("point_id").asLong());
        assertEquals("interrogation", v.get(0).path("cause").asText());
        assertEquals(12, v.get(1).path("point_id").asLong());
        assertEquals(1.0, v.get(1).path("value").asDouble());
        // IOA 9999 eslenmemis; IOA 1001'deki mv noktasi tip uyusmadigi icin atlanir
        assertEquals(2, v.size());

        Instant t = Instant.parse("2026-11-20T10:00:00.125Z");
        slave.send(asdu(31, 3, 7, 2001, concat(new byte[] {0x02}, cp56(t))));
        waitFor(() -> values().size() >= 3, 5000);
        JsonNode s = values().get(2);
        assertEquals(12, s.path("point_id").asLong());
        assertEquals(2.0, s.path("value").asDouble());
        assertEquals("spontaneous", s.path("cause").asText());
        assertEquals(t.toString(), s.path("ts").asText());
        assertEquals("good", s.path("quality").asText());

        // h2c yukseltmesi denenmemeli (uvicorn POST govdesini kaybeder).
        assertTrue(upgradeHeaders.isEmpty(), "Upgrade basligi gonderildi: " + upgradeHeaders);

        JsonNode h = gw.healthJson();
        assertEquals("v1", h.path("config_version").asText());
        assertEquals(1, h.path("ieds").size()); // modbus IED bu surumde atlanir
        assertTrue(h.path("ieds").get(0).path("connected").asBoolean());
    }

    @Test
    void hat_koparsa_noktalar_gecersiz_olur() throws Exception {
        startGateway(TOKEN);
        waitFor(() -> values().size() >= 2, 10000);
        slave.close(); // istasyon tamamen gitti
        waitFor(() -> values().stream().filter(n -> "comm_lost".equals(n.path("cause").asText())).count() == 3, 8000);
        JsonNode lost = values().stream().filter(n -> "comm_lost".equals(n.path("cause").asText())).findFirst()
                .orElseThrow();
        assertTrue(lost.path("value").isNull());
        assertEquals("invalid", lost.path("quality").asText());
        // Yeniden deneme basarisizliklari tekrar tekrar "gecersiz" yollamaz.
        Thread.sleep(2500);
        assertEquals(3, values().stream().filter(n -> "comm_lost".equals(n.path("cause").asText())).count());
    }

    @Test
    void Grid_gecici_hata_verirse_deger_kaybolmaz() throws Exception {
        failNextPosts.set(2);
        startGateway(TOKEN);
        waitFor(() -> values().size() >= 2, 15000);
        assertEquals(11, values().get(0).path("point_id").asLong());
        assertEquals(12, values().get(1).path("point_id").asLong());
    }

    @Test
    void yanlis_belirtecle_baglanti_kurulmaz() throws Exception {
        startGateway("yanlis");
        waitFor(() -> unauthorized.get() >= 1, 5000);
        assertFalse(slave.started());
        assertEquals(0, gw.healthJson().path("ieds").size());
    }

    @Test
    void yapilandirma_degisince_IED_yeniden_kurulur() throws Exception {
        startGateway(TOKEN);
        waitFor(() -> slave.interrogations.get() == 1, 10000);
        config = configJson(slave.port(), 2).replace("\"gi_interval_sec\":0", "\"gi_interval_sec\":3600");
        waitFor(() -> slave.connections.get() == 2, 8000);
        waitFor(() -> "v2".equals(gw.healthJson().path("config_version").asText()), 5000);
        // Degismeyen yapilandirma baglantiyi koparmaz.
        Thread.sleep(2500);
        assertEquals(2, slave.connections.get());
    }

    @Test
    void sayi_olmayan_olcum_gecersiz_gider() throws Exception {
        config = config.replace(
                "{\"id\":13,",
                "{\"id\":14,\"code\":\"M1/REL1/U\",\"point_type\":\"mv\",\"address\":{\"type_id\":13,\"ioa\":3001}},{\"id\":13,");
        startGateway(TOKEN);
        waitFor(() -> values().size() >= 2, 10000);
        int nan = Float.floatToIntBits(Float.NaN);
        slave.send(asdu(13, 3, 7, 3001, (byte) nan, (byte) (nan >> 8), (byte) (nan >> 16), (byte) (nan >> 24), (byte) 0));
        waitFor(() -> values().stream().anyMatch(n -> n.path("point_id").asLong() == 14), 5000);
        JsonNode v = values().stream().filter(n -> n.path("point_id").asLong() == 14).findFirst().orElseThrow();
        assertTrue(v.path("value").isNull());
        assertEquals("invalid", v.path("quality").asText());
    }

    @Test
    void modul_kapali_ya_da_lisans_gecersizse_tum_IEDler_durur() throws Exception {
        startGateway(TOKEN);
        waitFor(() -> values().size() >= 2, 10000);
        assertEquals(1, gw.healthJson().path("ieds").size());

        // 500: gecici Grid hatasi; mevcut baglantilar korunur.
        configStatus = 500;
        Thread.sleep(2500);
        assertEquals(1, gw.healthJson().path("ieds").size());
        assertEquals(1, slave.connections.get());
        assertEquals(0, slave.disconnects.get());
        assertTrue(gw.healthJson().path("module_active").asBoolean());

        // 404: SA modulu kapali -> tum IED'ler kapanir, gateway bosta bekler.
        configStatus = 404;
        waitFor(() -> !gw.healthJson().path("module_active").asBoolean(), 5000);
        assertEquals(0, gw.healthJson().path("ieds").size());
        waitFor(() -> slave.disconnects.get() == 1, 5000);
        Thread.sleep(2500); // yapilandirma yoklamasi surer ama yeniden baglanilmaz
        assertEquals(1, slave.connections.get());

        // Modul geri acilinca IED yeniden kurulur.
        configStatus = 200;
        waitFor(() -> slave.connections.get() == 2, 8000);
        waitFor(() -> gw.healthJson().path("module_active").asBoolean()
                && gw.healthJson().path("ieds").size() == 1, 5000);

        // 403: lisans gecersiz -> ayni davranis.
        configStatus = 403;
        waitFor(() -> gw.healthJson().path("ieds").size() == 0, 5000);
        waitFor(() -> slave.disconnects.get() == 2, 5000);
        assertFalse(gw.healthJson().path("module_active").asBoolean());
        configStatus = 200;
        waitFor(() -> slave.connections.get() == 3, 8000);
    }

    @Test
    void iec61850_IED_uctan_uca_Gride_ulasir() throws Exception {
        try (TestIed ied = new TestIed()) {
            config = """
                    {"config_version":"v61850","ieds":[
                      {"id":1,"code":"REL1","protocol":"iec104",
                       "connection":{"host":"127.0.0.1","port":%d,"common_address":7,"originator_address":0,
                                     "k":12,"w":8,"t0":5,"t1":15,"t2":10,"t3":20,"gi_interval_sec":0,"redundant_host":null},
                       "points":[{"id":11,"code":"M1/REL1/K1.Alarm","point_type":"sp","address":{"type_id":1,"ioa":1001}}]},
                      {"id":3,"code":"REL3","protocol":"iec61850",
                       "connection":{"host":"127.0.0.1","port":%d,"ap_title":"1,1,1,999,1","ae_qualifier":12,
                                     "report_control_blocks":["E1TESTLD0/LLN0$RP$urcbMeas"],
                                     "gi_on_connect":true,"poll_fallback_ms":0},
                       "points":[
                         {"id":31,"code":"M1/REL3/Q0.Pos","point_type":"dp","address":{"ref":"E1TESTLD0/XCBR1.Pos.stVal","fc":"ST"}},
                         {"id":32,"code":"M1/REL3/IL1","point_type":"mv","address":{"ref":"E1TESTLD0/MMXU1.A.phsA.cVal.mag.f","fc":"MX"}},
                         {"id":33,"code":"M1/REL3/Ad","point_type":"txt","address":{"ref":"E1TESTLD0/GGIO1.Ind1.stVal","fc":"ST"}}
                       ]}
                    ]}""".formatted(slave.port(), ied.port());
            startGateway(TOKEN);
            waitFor(() -> values().stream().anyMatch(n -> n.path("point_id").asLong() == 31)
                    && values().stream().anyMatch(n -> n.path("point_id").asLong() == 32), 15000);
            JsonNode pos = values().stream().filter(n -> n.path("point_id").asLong() == 31).findFirst().orElseThrow();
            assertEquals(2.0, pos.path("value").asDouble());
            assertEquals("good", pos.path("quality").asText());
            assertEquals("interrogation", pos.path("cause").asText());
            assertEquals(TestIed.T0.toString(), pos.path("ts").asText());
            JsonNode ia = values().stream().filter(n -> n.path("point_id").asLong() == 32).findFirst().orElseThrow();
            assertEquals(12.5, ia.path("value").asDouble());

            waitFor(() -> {
                JsonNode h = gw.healthJson();
                for (JsonNode i : h.path("ieds")) {
                    if ("iec61850".equals(i.path("protocol").asText())) {
                        return i.path("connected").asBoolean() && i.path("rcb_enabled").asInt() == 1;
                    }
                }
                return false;
            }, 5000);
            Instant t = Instant.parse("2026-11-21T07:45:00.125Z");
            ied.setPos(DoubleBitPos.OFF, Validity.GOOD, t);
            waitFor(() -> values().stream().anyMatch(n -> n.path("point_id").asLong() == 31
                    && "spontaneous".equals(n.path("cause").asText())), 5000);
            JsonNode s = values().stream().filter(n -> n.path("point_id").asLong() == 31
                    && "spontaneous".equals(n.path("cause").asText())).findFirst().orElseThrow();
            assertEquals(1.0, s.path("value").asDouble());
            assertEquals(t.toString(), s.path("ts").asText());

            JsonNode h = gw.healthJson();
            assertEquals(2, h.path("ieds").size());
            assertTrue(values().stream().noneMatch(n -> n.path("point_id").asLong() == 33)); // txt gonderilmez
            for (JsonNode i : h.path("ieds")) {
                if ("iec61850".equals(i.path("protocol").asText())) {
                    assertEquals(2, i.path("points").asInt());
                    assertTrue(i.path("reports").asLong() >= 1);
                } else {
                    assertEquals("iec104", i.path("protocol").asText());
                    assertTrue(i.has("asdu"));
                }
            }
        }
    }

    private static String config61850(int port, String version, String points) {
        return config61850(port, version, 3, "T61850", points);
    }

    private static String config61850(int port, String version, int id, String code, String points) {
        return """
                {"config_version":"%s","ieds":[
                  {"id":%d,"code":"%s","protocol":"iec61850",
                   "connection":{"host":"127.0.0.1","port":%d,
                                 "report_control_blocks":["E1TESTLD0/LLN0.BR.brcbStat","E1TESTLD0/LLN0$RP$urcbMeas"],
                                 "gi_on_connect":true,"poll_fallback_ms":0},
                   "points":[%s]}
                ]}""".formatted(version, id, code, port, points);
    }

    /** Ikinci istemcinin gordugu: gateway'in kullandigi bloklar bos (RptEna kapali, rezerve degil). */
    private static void assertRcbsFree(TestIed.Client other) throws Exception {
        assertFalse(other.enabled("brcbStat"));
        for (String u : List.of("urcbMeas01", "urcbMeas02")) {
            assertFalse(other.enabled(u), u);
            assertFalse(other.reserved(u), u);
        }
    }

    private JsonNode ied61850() {
        for (JsonNode i : gw.healthJson().path("ieds")) {
            if ("iec61850".equals(i.path("protocol").asText())) return i;
        }
        return JSON.missingNode();
    }

    @Test
    void iec61850_IED_degisince_ya_da_silinince_RCBler_birakilir() throws Exception {
        String pos = "{\"id\":31,\"code\":\"M1/T/Q0.Pos\",\"point_type\":\"dp\","
                + "\"address\":{\"ref\":\"E1TESTLD0/XCBR1.Pos.stVal\",\"fc\":\"ST\"}}";
        String ia = "{\"id\":32,\"code\":\"M1/T/IL1\",\"point_type\":\"mv\","
                + "\"address\":{\"ref\":\"E1TESTLD0/MMXU1.A.phsA.cVal.mag.f\",\"fc\":\"MX\"}}";
        try (TestIed ied = new TestIed()) {
            config = config61850(ied.port(), "v1", pos);
            startGateway(TOKEN);
            // brcbStat ve urcbMeas ayni Ds1'i tasir: yalniz BRCB acilir (rapor iki yoldan gelmesin).
            waitFor(() -> ied61850().path("rcb_enabled").asInt() == 1, 15000);
            try (TestIed.Client other = new TestIed.Client(ied.port())) {
                assertTrue(other.enabled("brcbStat"));
                assertFalse(other.enabled("urcbMeas01"));
                assertFalse(other.enabled("urcbMeas02"));

                // Grid noktalari degistirir: IED durdurulur ve hemen yeniden kurulur.
                config = config61850(ied.port(), "v2", pos + "," + ia);
                waitFor(() -> ied61850().path("points").asInt() == 2
                        && ied61850().path("rcb_enabled").asInt() == 1, 15000);
                // Eski iliski BRCB'yi birakti: ayni BRCB yeniden alindi.
                assertTrue(other.enabled("brcbStat"));
                assertFalse(other.enabled("urcbMeas01"));
                assertFalse(other.reserved("urcbMeas01"));
                assertFalse(other.reserved("urcbMeas02"));
                assertTrue(ied61850().path("connected").asBoolean());
                assertEquals("", ied61850().path("last_error").asText());

                // Grid IED'yi siler: bloklar birakilir. Bean sunucusu kapanan iliskinin
                // RptEna / Resv'ini kendiliginden hic birakmaz; birakan gateway'dir.
                config = "{\"config_version\":\"v3\",\"ieds\":[]}";
                waitFor(() -> gw.healthJson().path("ieds").isEmpty(), 15000);
                assertRcbsFree(other);

                // Ayni sunucuya yeni bir IED: ayni bloklari alir.
                config = config61850(ied.port(), "v4", 4, "T61850B", pos);
                waitFor(() -> ied61850().path("rcb_enabled").asInt() == 1, 15000);
                assertEquals("T61850B", ied61850().path("code").asText());
                assertTrue(other.enabled("brcbStat"));
                assertFalse(other.enabled("urcbMeas01"));
                assertFalse(other.enabled("urcbMeas02"));
            }
        }
    }

    private static String discoverJob(long id, int port) {
        return """
                {"id":%d,"kind":"iec61850_discover",
                 "params":{"host":"127.0.0.1","port":%d,"ap_title":null,"ae_qualifier":null,"timeout_ms":3000}}"""
                .formatted(id, port);
    }

    private static JsonNode find(JsonNode arr, String ref) {
        for (JsonNode n : arr) {
            if (ref.equals(n.path("ref").asText())) return n;
        }
        return JSON.missingNode();
    }

    @Test
    void kesif_isi_sonucu_Gride_gider() throws Exception {
        try (TestIed ied = new TestIed()) {
            jobQueue.add(discoverJob(17, ied.port()));
            startGateway(TOKEN);
            waitFor(() -> jobResults.size() == 1, 15000);
            assertEquals("/api/v1/sa/gateway/jobs/17/result", jobResultPaths.get(0));
            JsonNode body = jobResults.get(0);
            assertTrue(body.path("ok").asBoolean(), body.toString());
            JsonNode r = body.path("result");
            assertEquals("[\"E1TESTLD0\"]", r.path("logical_devices").toString());
            assertFalse(r.path("truncated").asBoolean());

            // Yapraklar: yalniz ST / MX; tip bean'in MMS uzerinden okudugu modeldeki tip.
            JsonNode nodes = r.path("nodes");
            String[][] want = {
                {"E1TESTLD0/XCBR1.Pos.stVal", "ST", "DOUBLE_BIT_POS"},
                {"E1TESTLD0/XCBR1.Pos.q", "ST", "QUALITY"},
                {"E1TESTLD0/XCBR1.Pos.t", "ST", "TIMESTAMP"},
                {"E1TESTLD0/MMXU1.A.phsA.cVal.mag.f", "MX", "FLOAT32"},
                {"E1TESTLD0/MMXU1.A.phsA.q", "MX", "QUALITY"},
                {"E1TESTLD0/MMXU1.TotW.mag.f", "MX", "FLOAT32"},
                {"E1TESTLD0/GGIO1.Ind1.stVal", "ST", "BOOLEAN"},
                {"E1TESTLD0/GGIO1.IntIn1.stVal", "ST", "INT32"},
                {"E1TESTLD0/MMTR1.TotWh.actVal", "ST", "INT64"},
                {"E1TESTLD0/XCBR1.Beh.stVal", "ST", "INT8"},
            };
            for (String[] w : want) {
                JsonNode n = find(nodes, w[0]);
                assertEquals(w[1], n.path("fc").asText(), w[0]);
                assertEquals(w[2], n.path("type").asText(), w[0]);
            }
            for (JsonNode n : nodes) {
                String fc = n.path("fc").asText();
                assertTrue("ST".equals(fc) || "MX".equals(fc), n.toString());
            }
            assertTrue(find(nodes, "E1TESTLD0/XCBR1.Pos.ctlModel").isMissingNode()); // CF

            JsonNode ds = find(r.path("datasets"), "E1TESTLD0/LLN0.Ds1");
            assertEquals("[{\"ref\":\"E1TESTLD0/XCBR1.Pos\",\"fc\":\"ST\"},"
                    + "{\"ref\":\"E1TESTLD0/MMXU1.A.phsA\",\"fc\":\"MX\"},"
                    + "{\"ref\":\"E1TESTLD0/GGIO1.Ind1\",\"fc\":\"ST\"}]", ds.path("members").toString());

            JsonNode rcbs = r.path("rcbs");
            assertEquals(3, rcbs.size());
            JsonNode br = find(rcbs, "E1TESTLD0/LLN0.BR.brcbStat");
            assertTrue(br.path("buffered").asBoolean());
            assertEquals("E1TESTLD0/LLN0.Ds1", br.path("dataset").asText());
            assertFalse(br.path("enabled").asBoolean());
            for (String u : List.of("E1TESTLD0/LLN0.RP.urcbMeas01", "E1TESTLD0/LLN0.RP.urcbMeas02")) {
                JsonNode rp = find(rcbs, u);
                assertFalse(rp.path("buffered").asBoolean(true), u);
                assertEquals("E1TESTLD0/LLN0.Ds1", rp.path("dataset").asText(), u);
                assertFalse(rp.path("enabled").asBoolean(true), u);
            }

            // Saglik sonuc yollandiktan sonra guncellenir.
            waitFor(() -> gw.healthJson().path("jobs").path("last_id").asLong() == 17, 5000);
            JsonNode h = gw.healthJson().path("jobs");
            assertEquals("ok", h.path("last_status").asText());
            assertFalse(h.path("last_at").isNull());
            assertEquals("", h.path("poll_error").asText());
            assertTrue(upgradeHeaders.isEmpty(), "Upgrade basligi gonderildi: " + upgradeHeaders);
        }
    }

    @Test
    void kesif_sonucu_dugum_sinirinda_kesilir() throws Exception {
        com.beanit.iec61850bean.ServerModel model;
        try (java.io.InputStream in = TestIed.class.getResourceAsStream("/iec61850/e1test.icd")) {
            model = com.beanit.iec61850bean.SclParser.parse(in).get(0);
        }
        JsonNode full = JobWorker.discoveryResult(model, JobWorker.MAX_NODES);
        assertFalse(full.path("truncated").asBoolean());
        assertEquals(36, full.path("nodes").size());
        JsonNode cut = JobWorker.discoveryResult(model, 5);
        assertTrue(cut.path("truncated").asBoolean());
        assertEquals(5, cut.path("nodes").size());
        assertEquals("[\"E1TESTLD0\"]", cut.path("logical_devices").toString());
        assertEquals(3, cut.path("rcbs").size()); // kesme yalniz dugumlere uygulanir
    }

    @Test
    void kesif_isi_hatasi_ok_false_ile_doner() throws Exception {
        int closed;
        try (java.net.ServerSocket s = new java.net.ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            closed = s.getLocalPort();
        }
        jobQueue.add(discoverJob(21, closed));
        jobQueue.add("{\"id\":22,\"kind\":\"modbus_scan\",\"params\":{}}");
        jobQueue.add("{\"id\":23,\"kind\":\"iec61850_discover\",\"params\":{\"port\":102}}");
        startGateway(TOKEN);
        waitFor(() -> jobResults.size() == 3, 15000);
        assertEquals(List.of("/api/v1/sa/gateway/jobs/21/result", "/api/v1/sa/gateway/jobs/22/result",
                "/api/v1/sa/gateway/jobs/23/result"), jobResultPaths);

        JsonNode refused = jobResults.get(0);
        assertFalse(refused.path("ok").asBoolean(true));
        assertTrue(refused.path("result").isMissingNode());
        String err = refused.path("error").asText();
        assertTrue(err.startsWith("baglanti kurulamadi: "), err);
        assertTrue(err.length() <= 300, err);

        assertFalse(jobResults.get(1).path("ok").asBoolean(true));
        assertEquals("desteklenmeyen is turu: modbus_scan", jobResults.get(1).path("error").asText());
        assertFalse(jobResults.get(2).path("ok").asBoolean(true));
        assertTrue(jobResults.get(2).path("error").asText().startsWith("gecersiz parametre: "),
                jobResults.get(2).toString());

        waitFor(() -> gw.healthJson().path("jobs").path("last_id").asLong() == 23, 5000);
        JsonNode h = gw.healthJson().path("jobs");
        assertEquals("error", h.path("last_status").asText());
    }

    @Test
    void is_ucu_404_donerse_modul_kapanmaz_yoklama_seyrelir() throws Exception {
        jobsStatus = 404; // eski Grid: uc yok
        startGateway(TOKEN);
        waitFor(() -> values().size() >= 2, 10000);
        waitFor(() -> jobPolls.get() >= 1, 5000);
        // Normalde 200 ms'de bir yoklanir; 404'ten sonra 60 sn beklenir.
        Thread.sleep(2500);
        assertEquals(1, jobPolls.get());
        JsonNode h = gw.healthJson();
        assertTrue(h.path("module_active").asBoolean());
        assertEquals(1, h.path("ieds").size());
        assertTrue(h.path("ieds").get(0).path("connected").asBoolean());
        assertEquals(1, slave.connections.get());
        assertEquals(0, slave.disconnects.get());
        assertTrue(h.path("jobs").path("poll_error").asText().startsWith("HTTP 404"), h.toString());
        assertTrue(h.path("jobs").path("last_id").isNull());
        assertFalse(h.path("last_config_ok").isNull()); // yapilandirma yoklamasi surer
    }

    @Test
    void kuyruk_dolunca_en_eski_atilir() {
        String base = "http://127.0.0.1:1/api/v1";
        SaGateway g = new SaGateway(new GridClient(base, TOKEN),
                new SaGateway.Options(60, 100, 1000, 3, 0, ZoneOffset.UTC));
        for (int i = 1; i <= 5; i++) g.enqueue(new ValueItem(i, (double) i, "good", null, "spontaneous"));
        assertEquals(3, g.queueSize());
        assertEquals(2, g.healthJson().path("dropped").asLong());
    }

    @Test
    void tip_ailesi_eslemesi() {
        assertTrue(Iec104Ied.compatible(TypeId.M_DP_NA_1, "dp"));
        assertTrue(Iec104Ied.compatible(TypeId.M_DP_TB_1, "dp"));
        assertTrue(Iec104Ied.compatible(TypeId.M_ME_NC_1, "mv"));
        assertTrue(Iec104Ied.compatible(TypeId.M_BO_NA_1, "st"));
        assertFalse(Iec104Ied.compatible(TypeId.M_SP_NA_1, "dp"));
        assertFalse(Iec104Ied.compatible(TypeId.C_IC_NA_1, "sp"));
        assertNull(TypeId.of(38));
    }
}

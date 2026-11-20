package com.enerjione.sagateway.gateway;

import static com.enerjione.sagateway.iec104.TestSlave.asdu;
import static com.enerjione.sagateway.iec104.TestSlave.concat;
import static com.enerjione.sagateway.iec104.TestSlave.cp56;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.enerjione.sagateway.grid.GridClient;
import com.enerjione.sagateway.grid.GridClient.ValueItem;
import com.enerjione.sagateway.iec104.TestSlave;
import com.enerjione.sagateway.iec104.TypeId;
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

    @BeforeEach
    void setUp() throws Exception {
        slave = new TestSlave(7);
        slave.giData.add(asdu(1, 20, 7, 1001, (byte) 0x01)); // sp -> nokta 11
        slave.giData.add(asdu(3, 20, 7, 2001, (byte) 0x01)); // dp -> nokta 12
        slave.giData.add(asdu(1, 20, 7, 9999, (byte) 0x01)); // eslenmemis
        config = configJson(slave.port(), 1);

        grid = HttpServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0);
        grid.createContext("/api/v1/sa/gateway/SAGW1/", ex -> {
            String up = ex.getRequestHeaders().getFirst("Upgrade");
            if (up != null) upgradeHeaders.add(up);
            String body;
            int status = 200;
            if (!TOKEN.equals(ex.getRequestHeaders().getFirst("X-Gateway-Token"))) {
                unauthorized.incrementAndGet();
                status = 401;
                body = "{\"detail\":\"Invalid gateway token\"}";
            } else if (ex.getRequestURI().getPath().endsWith("/config")) {
                body = config;
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
                {"gateway_code":"SAGW1","config_version":"v%d","ieds":[
                  {"id":1,"code":"REL1","protocol":"iec104",
                   "connection":{"host":"127.0.0.1","port":%d,"common_address":7,"originator_address":0,
                                 "k":12,"w":8,"t0":5,"t1":15,"t2":10,"t3":20,"gi_interval_sec":0,"redundant_host":null},
                   "points":[
                     {"id":11,"code":"M1/REL1/K1.Alarm","point_type":"sp","address":{"type_id":1,"ioa":1001}},
                     {"id":12,"code":"M1/REL1/Q0.Pos","point_type":"dp","address":{"type_id":31,"ioa":2001}},
                     {"id":13,"code":"M1/REL1/IL1","point_type":"mv","address":{"type_id":13,"ioa":1001}}
                   ]},
                  {"id":2,"code":"REL2","protocol":"iec61850","connection":{"host":"127.0.0.1"},"points":[]}
                ]}""".formatted(version, port);
    }

    private SaGateway startGateway(String token) throws Exception {
        String base = "http://127.0.0.1:" + grid.getAddress().getPort() + "/api/v1";
        gw = new SaGateway(new GridClient(base, "SAGW1", token),
                new SaGateway.Options(1, 100, 1000, 100_000, 0, ZoneOffset.UTC));
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
        assertEquals(1, h.path("ieds").size()); // 61850 IED bu surumde atlanir
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
    void kuyruk_dolunca_en_eski_atilir() {
        String base = "http://127.0.0.1:1/api/v1";
        SaGateway g = new SaGateway(new GridClient(base, "SAGW1", TOKEN),
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

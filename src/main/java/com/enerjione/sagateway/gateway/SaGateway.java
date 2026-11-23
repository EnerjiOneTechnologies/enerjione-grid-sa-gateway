package com.enerjione.sagateway.gateway;

import com.enerjione.sagateway.grid.GridClient;
import com.enerjione.sagateway.grid.GridClient.ValueItem;
import com.enerjione.sagateway.iec104.Iec104Settings;
import com.enerjione.sagateway.iec61850.Iec61850Client;
import com.enerjione.sagateway.iec61850.Iec61850Ied;
import com.enerjione.sagateway.iec61850.RcbRef;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.LongAdder;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Gateway calisma modu: Grid'den yapilandirmayi ceker, IED baglantilarini
 * kurar, degerleri partiler halinde Grid'e yollar.
 *
 * <p>Teslim kurali: Grid'e ulasmayan parti KAYBOLMAZ, kuyrugun basina geri
 * konur ve tekrar denenir. Kuyruk sinirlidir; dolarsa en ESKI deger atilir
 * (sayilir ve gunluge yazilir) ki bellek sinirsiz buyumesin.
 *
 * <p>Modul kapali (yapilandirma 404) ya da lisans gecersiz (403) ise TUM IED
 * baglantilari kapatilir ve gateway bosta bekler; yapilandirma yoklamasi normal
 * araliginda surer. Kuyruktaki degerler teslim kuralina gore tutulur (atilmaz);
 * modul donunce yeniden kurulan IED'lerin ilk okumasi onlari gunceller. Ag
 * hatasi gibi diger hatalarda mevcut baglantilar calismaya devam eder.
 *
 * <p>Is kanali (kesif) ayri is parcacigindadir ({@link JobWorker}); is ucunun
 * hatasi modul durumunu etkilemez.
 */
public final class SaGateway implements AutoCloseable {

    private static final Logger LOG = Logger.getLogger(SaGateway.class.getName());
    private static final ObjectMapper JSON = new ObjectMapper();

    /** Calisma ayarlari (ortam degiskenlerinden). */
    public record Options(int configRefreshSec, int batchMs, int batchMax, int queueMax, int healthPort,
                          ZoneId deviceZone) {
    }

    private final GridClient grid;
    private final Options opt;
    private final LinkedBlockingDeque<ValueItem> queue;
    private final Map<Long, IedHandler> ieds = new LinkedHashMap<>();
    private final Set<String> unsupportedLogged = new HashSet<>();
    private final JobWorker jobs;

    private volatile boolean stopped;
    private volatile String configVersion = "";
    /** Grid modulun kapali / lisansin gecersiz oldugunu soyledi (404 / 403). */
    private volatile boolean inactive;
    private volatile Instant lastConfigOk;
    private volatile Instant lastPostOk;
    private volatile String lastPostError = "";
    private volatile long sent;
    private volatile long dropped;
    /**
     * Son gonderilen deger (nokta basina). Periyodik tekrar (yoklama, 104 devirli
     * olcum, butunluk raporu) degismemisse gonderilmez: raporsuz bir IED'de her
     * turda yuzlerce ayni deger Grid'e ve diske yuk olurdu. Bilgi icin
     * {@link #periodicRefreshNs} dolunca yine gider. Kendiliginden rapor, genel
     * sorgu ve baglanti kaybi HER ZAMAN gider.
     */
    private final ConcurrentHashMap<Long, LastSent> lastSent = new ConcurrentHashMap<>();
    private final LongAdder suppressed = new LongAdder();
    private volatile long periodicRefreshNs = Duration.ofMinutes(10).toNanos();

    private record LastSent(Double value, String quality, long atNs) {
    }
    private Thread configThread;
    private Thread postThread;
    private HttpServer health;

    public SaGateway(GridClient grid, Options opt) {
        this.grid = grid;
        this.opt = opt;
        this.queue = new LinkedBlockingDeque<>();
        this.jobs = new JobWorker(grid);
    }

    /** Testler icin: degismeyen periyodik degerin yine gonderilme araligi. */
    void periodicRefreshMs(long ms) {
        periodicRefreshNs = ms * 1_000_000L;
    }

    /** Testler icin: is yoklama araligi ve hata sonrasi bekleme (start'tan once). */
    void jobIntervals(long pollMs, long backoffMs) {
        jobs.intervals(pollMs, backoffMs);
    }

    public void start() throws IOException {
        configThread = new Thread(this::configLoop, "sa-config");
        configThread.setDaemon(true);
        configThread.start();
        postThread = new Thread(this::postLoop, "sa-post");
        postThread.setDaemon(true);
        postThread.start();
        jobs.start();
        if (opt.healthPort() > 0) startHealth();
    }

    @Override
    public void close() {
        stopped = true;
        if (configThread != null) configThread.interrupt();
        if (postThread != null) postThread.interrupt();
        jobs.close();
        synchronized (ieds) {
            closeAll(ieds.values());
            ieds.clear();
        }
        if (health != null) health.stop(0);
    }

    /**
     * IED'leri paralel kapatir ve hepsini bekler. 61850 IED'si kapanirken RCB'lerini
     * birakir (birkac sn surebilir); sirayla kapatmak toplam sureyi IED sayisiyla buyutur.
     */
    private static void closeAll(Collection<IedHandler> handlers) {
        List<Thread> ts = new ArrayList<>();
        for (IedHandler h : handlers) ts.add(Thread.ofVirtual().name("sa-close-" + h.code()).start(h::close));
        for (Thread t : ts) {
            try {
                t.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    // ---- kuyruk ---------------------------------------------------------------

    void enqueue(ValueItem v) {
        long now = System.nanoTime();
        if ("periodic".equals(v.cause())) {
            LastSent l = lastSent.get(v.pointId());
            if (l != null && Objects.equals(l.value(), v.value()) && Objects.equals(l.quality(), v.quality())
                    && now - l.atNs() < periodicRefreshNs) {
                suppressed.increment();
                return;
            }
        }
        lastSent.put(v.pointId(), new LastSent(v.value(), v.quality(), now));
        while (queue.size() >= opt.queueMax()) {
            if (queue.pollFirst() == null) break;
            // Dusen deger Grid'e hic ulasmadi: bellek temizlenir, siradaki turda
            // her nokta yeniden gider (Grid eski degerde kalmaz).
            lastSent.clear();
            long d = ++dropped;
            if (d == 1 || d % 1000 == 0) {
                LOG.warning(() -> "sa_queue_full dusen_toplam=" + d + " (Grid'e ulasilamiyor ya da yavas)");
            }
        }
        queue.offerLast(v);
    }

    int queueSize() {
        return queue.size();
    }

    // ---- yapilandirma ---------------------------------------------------------

    private void configLoop() {
        while (!stopped) {
            try {
                JsonNode cfg = grid.fetchConfig();
                if (inactive) {
                    inactive = false;
                    LOG.info("sa_module_active (yapilandirma yeniden alindi, IED'ler kuruluyor)");
                }
                apply(cfg);
                lastConfigOk = Instant.now();
            } catch (InterruptedException e) {
                return;
            } catch (GridClient.GridHttpException e) {
                if (e.status == 404 || e.status == 403) {
                    deactivate(e);
                } else {
                    LOG.warning(() -> "sa_config_fetch_failed " + e.getMessage()
                            + " (mevcut baglantilar calismaya devam eder)");
                }
            } catch (Exception e) {
                LOG.warning(() -> "sa_config_fetch_failed " + e.getMessage()
                        + " (mevcut baglantilar calismaya devam eder)");
            }
            sleep(opt.configRefreshSec() * 1000L);
        }
    }

    /**
     * 404 (SA modulu kapali) / 403 (lisans gecersiz): tum IED'ler kapanir, gateway
     * bosta bekler. Gunluge yalniz duruma GECISTE yazilir.
     */
    private void deactivate(GridClient.GridHttpException e) {
        int closed;
        synchronized (ieds) {
            closed = ieds.size();
            closeAll(ieds.values());
            ieds.clear();
        }
        configVersion = "";
        if (!inactive) {
            inactive = true;
            String why = e.status == 404 ? "SA modulu kapali" : "lisans gecersiz";
            int c = closed;
            LOG.warning(() -> "sa_module_inactive http=" + e.status + " (" + why + ") kapatilan_ied=" + c
                    + " (yapilandirma yoklamasi surer)");
        }
    }

    /** Yapilandirmayi uygular: degisen IED yeniden kurulur, digerlerine dokunulmaz. */
    void apply(JsonNode cfg) {
        String version = cfg.path("config_version").asText("");
        // Silinen noktalarin son degeri bellekte kalmasin.
        Set<Long> noktalar = new HashSet<>();
        for (JsonNode ied : cfg.path("ieds")) {
            for (JsonNode p : ied.path("points")) noktalar.add(p.path("id").asLong());
        }
        lastSent.keySet().retainAll(noktalar);
        Map<Long, JsonNode> wanted = new HashMap<>();
        for (JsonNode ied : cfg.path("ieds")) {
            String protocol = ied.path("protocol").asText();
            if (!"iec104".equals(protocol) && !"iec61850".equals(protocol)) {
                String c = ied.path("code").asText();
                if (unsupportedLogged.add(c + "/" + protocol)) {
                    LOG.info(() -> "sa_ied_protocol_not_supported ied=" + c + " protokol=" + protocol
                            + " (bu surumde iec104 ve iec61850)");
                }
                continue;
            }
            wanted.put(ied.path("id").asLong(), ied);
        }
        synchronized (ieds) {
            List<IedHandler> stop = new ArrayList<>();
            for (Long id : new ArrayList<>(ieds.keySet())) {
                JsonNode w = wanted.get(id);
                if (w == null || !fingerprint(w).equals(ieds.get(id).fingerprint())) {
                    IedHandler old = ieds.remove(id);
                    LOG.info(() -> "sa_ied_stop ied=" + old.code() + (w == null ? " (kaldirildi)" : " (degisti)"));
                    stop.add(old);
                }
            }
            // Eskiler tamamen kapanmadan (RCB'ler birakilmadan) yenisi baslamaz.
            closeAll(stop);
            for (Map.Entry<Long, JsonNode> e : wanted.entrySet()) {
                if (ieds.containsKey(e.getKey())) continue;
                try {
                    IedHandler ied = build(e.getValue());
                    ieds.put(e.getKey(), ied);
                    LOG.info(() -> "sa_ied_start ied=" + ied.code() + " protokol=" + ied.protocol()
                            + " nokta=" + ied.pointCount());
                    ied.start();
                } catch (RuntimeException ex) {
                    LOG.log(Level.WARNING, "sa_ied_config_invalid ied=" + e.getValue().path("code").asText()
                            + " " + ex.getMessage());
                }
            }
        }
        if (!version.equals(configVersion)) {
            LOG.info(() -> "sa_config_applied version=" + version + " ied=" + ieds.size());
            configVersion = version;
        }
    }

    private static String fingerprint(JsonNode ied) {
        return ied.path("protocol").asText() + "|" + ied.path("connection").toString() + "|"
                + ied.path("points").toString();
    }

    private IedHandler build(JsonNode ied) {
        return "iec61850".equals(ied.path("protocol").asText()) ? build61850(ied) : build104(ied);
    }

    private Iec104Ied build104(JsonNode ied) {
        JsonNode c = ied.path("connection");
        String redundant = c.path("redundant_host").isTextual() ? c.path("redundant_host").asText() : null;
        Iec104Settings s = new Iec104Settings(
                c.path("host").asText(),
                c.path("port").asInt(2404),
                redundant,
                c.path("common_address").asInt(),
                c.path("originator_address").asInt(0),
                c.path("k").asInt(12),
                c.path("w").asInt(8),
                c.path("t0").asInt(30),
                c.path("t1").asInt(15),
                c.path("t2").asInt(10),
                c.path("t3").asInt(20),
                c.path("gi_interval_sec").asInt(0),
                opt.deviceZone());
        Map<Integer, List<Iec104Ied.PointRef>> byIoa = new HashMap<>();
        for (JsonNode p : ied.path("points")) {
            JsonNode a = p.path("address");
            if (!a.has("ioa")) continue;
            byIoa.computeIfAbsent(a.path("ioa").asInt(), k -> new ArrayList<>()).add(
                    new Iec104Ied.PointRef(p.path("id").asLong(), p.path("code").asText(), p.path("point_type").asText()));
        }
        return new Iec104Ied(ied.path("id").asLong(), ied.path("code").asText(), fingerprint(ied), s, byIoa,
                this::enqueue);
    }

    /**
     * Grid sozlesmesi: connection = {host, port (102), ap_title, ae_qualifier,
     * report_control_blocks, gi_on_connect (true), poll_fallback_ms (0)};
     * nokta adresi = {ref, fc}.
     */
    private Iec61850Ied build61850(JsonNode ied) {
        String code = ied.path("code").asText();
        JsonNode c = ied.path("connection");
        List<RcbRef> rcbs = new ArrayList<>();
        for (JsonNode r : c.path("report_control_blocks")) {
            try {
                rcbs.add(RcbRef.parse(r.asText()));
            } catch (IllegalArgumentException ex) {
                LOG.warning(() -> "sa_rcb_invalid ied=" + code + " " + ex.getMessage() + " (atlanir)");
            }
        }
        JsonNode ap = c.path("ap_title");
        JsonNode ae = c.path("ae_qualifier");
        Iec61850Client.Settings s = Iec61850Client.Settings.of(c.path("host").asText(), c.path("port").asInt(102))
                .withAcse(ap.isTextual() ? Iec61850Client.Settings.parseApTitle(ap.asText()) : null,
                        ae.isIntegralNumber() ? Integer.valueOf(ae.asInt()) : null)
                .withRcbs(rcbs)
                .withGiOnConnect(c.path("gi_on_connect").asBoolean(true))
                .withPollMs(c.path("poll_fallback_ms").asInt(0));
        List<Iec61850Ied.PointRef> points = new ArrayList<>();
        for (JsonNode p : ied.path("points")) {
            JsonNode a = p.path("address");
            if (!a.has("ref")) continue;
            points.add(new Iec61850Ied.PointRef(p.path("id").asLong(), p.path("code").asText(),
                    p.path("point_type").asText(), a.path("ref").asText(), a.path("fc").asText()));
        }
        return new Iec61850Ied(ied.path("id").asLong(), code, fingerprint(ied), s, points, this::enqueue);
    }

    // ---- Grid'e gonderim ------------------------------------------------------

    private void postLoop() {
        long backoff = 1000;
        while (!stopped) {
            List<ValueItem> batch = new ArrayList<>();
            try {
                ValueItem first = queue.pollFirst(opt.batchMs(), TimeUnit.MILLISECONDS);
                if (first == null) continue;
                batch.add(first);
                queue.drainTo(batch, opt.batchMax() - 1);
                GridClient.PostResult r = grid.postValues(batch);
                sent += r.accepted();
                lastPostOk = Instant.now();
                lastPostError = "";
                backoff = 1000;
                if (r.rejected() > 0) {
                    LOG.warning(() -> "sa_values_rejected adet=" + r.rejected() + " ornek="
                            + truncate(r.rejectedDetail().toString()));
                }
            } catch (InterruptedException e) {
                requeue(batch);
                return;
            } catch (GridClient.GridHttpException e) {
                lastPostError = e.getMessage();
                if (e.permanent()) {
                    lastSent.clear();
                    LOG.severe(() -> "sa_values_dropped adet=" + batch.size() + " " + e.getMessage());
                } else {
                    requeue(batch);
                    LOG.warning(() -> "sa_values_post_failed " + e.getMessage() + " (tekrar denenecek)");
                    sleep(backoff);
                    backoff = Math.min(30_000, backoff * 2);
                }
            } catch (IOException e) {
                lastPostError = e.toString();
                requeue(batch);
                LOG.warning(() -> "sa_values_post_failed " + e + " (tekrar denenecek)");
                sleep(backoff);
                backoff = Math.min(30_000, backoff * 2);
            }
        }
    }

    private void requeue(List<ValueItem> batch) {
        for (int i = batch.size() - 1; i >= 0; i--) {
            queue.offerFirst(batch.get(i));
        }
    }

    private static String truncate(String s) {
        return s.length() > 300 ? s.substring(0, 300) + "..." : s;
    }

    private void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ---- saglik ---------------------------------------------------------------

    ObjectNode healthJson() {
        ObjectNode n = JSON.createObjectNode();
        boolean gridOk = lastPostOk != null || (lastConfigOk != null && queue.isEmpty());
        n.put("status", lastConfigOk != null && gridOk ? "ok" : "degraded");
        n.put("config_version", configVersion);
        n.put("last_config_ok", lastConfigOk == null ? null : lastConfigOk.toString());
        n.put("last_post_ok", lastPostOk == null ? null : lastPostOk.toString());
        n.put("last_post_error", lastPostError);
        n.put("queue", queue.size());
        n.put("sent", sent);
        n.put("dropped", dropped);
        n.put("suppressed", suppressed.sum());
        n.put("module_active", !inactive);
        n.set("jobs", jobs.healthJson());
        ArrayNode arr = n.putArray("ieds");
        synchronized (ieds) {
            for (IedHandler ied : ieds.values()) {
                IedHandler.Status st = ied.status();
                ObjectNode i = arr.addObject();
                i.put("code", ied.code());
                i.put("protocol", ied.protocol());
                i.put("connected", st.connected());
                i.put("host", st.host());
                i.put("points", ied.pointCount());
                if ("iec104".equals(ied.protocol())) {
                    i.put("asdu", st.messages());
                } else {
                    i.put("reports", st.messages());
                    i.put("rcb_enabled", st.reportsEnabled());
                    if (st.pollMs() >= 0) i.put("poll_ms", st.pollMs());
                }
                i.put("reconnects", st.reconnects());
                i.put("last_error", st.lastError());
            }
        }
        return n;
    }

    private void startHealth() throws IOException {
        health = HttpServer.create(new InetSocketAddress(opt.healthPort()), 0);
        health.createContext("/health", ex -> {
            byte[] body = healthJson().toString().getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(200, body.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(body);
            }
        });
        health.start();
        LOG.info(() -> "sa_health_listening port=" + opt.healthPort());
    }
}

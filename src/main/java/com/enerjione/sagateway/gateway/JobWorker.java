package com.enerjione.sagateway.gateway;

import com.beanit.iec61850bean.BasicDataAttribute;
import com.beanit.iec61850bean.Brcb;
import com.beanit.iec61850bean.DataSet;
import com.beanit.iec61850bean.Fc;
import com.beanit.iec61850bean.FcModelNode;
import com.beanit.iec61850bean.ModelNode;
import com.beanit.iec61850bean.Rcb;
import com.beanit.iec61850bean.ServerModel;
import com.beanit.iec61850bean.ServiceError;
import com.enerjione.sagateway.grid.GridClient;
import com.enerjione.sagateway.iec61850.Iec61850Client;
import com.enerjione.sagateway.iec61850.RcbRef;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.time.Instant;
import java.util.Map;
import java.util.TreeMap;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Grid -> gateway is kanali (Grid 2.120+): {@code GET jobs} ile is alir,
 * calistirir, sonucu {@code POST jobs/{id}/result} ile yollar.
 *
 * <p>Kendi is parcacigindadir; kesif yapilandirma dongusunu ve deger gonderimini
 * bekletmez. Ayni anda tek is calisir. Is ucunun hatasi (404 = eski Grid ya da
 * modul kapali; 401/403/5xx/ag hatasi) modul kapatma yoluna GIRMEZ: yalniz is
 * yoklamasi {@link #BACKOFF_MS}'ye seyreltilir, gerisi calismaya devam eder.
 *
 * <p>Is turu {@code iec61850_discover}: IED'ye baglanir, modeli ve RCB
 * degerlerini okur, kapatir. Izleme baglantisi degildir; RCB etkinlestirilmez.
 */
final class JobWorker implements AutoCloseable {

    private static final Logger LOG = Logger.getLogger(JobWorker.class.getName());
    private static final ObjectMapper JSON = new ObjectMapper();

    static final long POLL_MS = 3000;
    static final long BACKOFF_MS = 60_000;
    /** Sonuctaki en fazla dugum; asilirsa kesilir ({@code truncated}). */
    static final int MAX_NODES = 50_000;
    private static final int MAX_ERROR = 300;
    private static final long[] POST_RETRY_MS = {2000, 5000};

    private final GridClient grid;
    private volatile long pollMs = POLL_MS;
    private volatile long backoffMs = BACKOFF_MS;
    private volatile boolean stopped;
    private Thread thread;

    // saglik
    private volatile Long lastId;
    private volatile String lastStatus = "";
    private volatile Instant lastAt;
    private volatile String pollError = "";

    JobWorker(GridClient grid) {
        this.grid = grid;
    }

    /** Testler icin: yoklama ve hata sonrasi bekleme araligi (start'tan once). */
    void intervals(long poll, long backoff) {
        pollMs = poll;
        backoffMs = backoff;
    }

    void start() {
        thread = new Thread(this::loop, "sa-jobs");
        thread.setDaemon(true);
        thread.start();
    }

    @Override
    public void close() {
        stopped = true;
        if (thread != null) thread.interrupt();
    }

    /** {@code /health} icin: son is ve yoklama hatasi (parametre / adres icermez). */
    ObjectNode healthJson() {
        ObjectNode n = JSON.createObjectNode();
        Long id = lastId;
        if (id == null) n.putNull("last_id");
        else n.put("last_id", id);
        n.put("last_status", lastStatus);
        Instant at = lastAt;
        n.put("last_at", at == null ? null : at.toString());
        n.put("poll_error", pollError);
        return n;
    }

    // ---- yoklama --------------------------------------------------------------

    private void loop() {
        while (!stopped) {
            long wait = pollMs;
            try {
                JsonNode r = grid.fetchJobs();
                if (!pollError.isEmpty()) {
                    LOG.info("sa_jobs_poll_ok (is yoklamasi normal aralikta)");
                    pollError = "";
                }
                for (JsonNode job : r.path("jobs")) {
                    if (stopped) break;
                    run(job);
                }
            } catch (InterruptedException e) {
                return;
            } catch (Exception e) {
                // 404 eski Grid'de beklenir; modul kapatma (deactivate) yolu BILEREK yok.
                boolean http = e instanceof GridClient.GridHttpException;
                String m = http ? e.getMessage() : describe(e);
                if (pollError.isEmpty()) {
                    Level lv = http && ((GridClient.GridHttpException) e).status == 404 ? Level.INFO : Level.WARNING;
                    LOG.log(lv, "sa_jobs_poll_failed " + m + " (is yoklamasi " + backoffMs / 1000
                            + " sn'ye seyreltildi; IED baglantilari etkilenmez)");
                }
                pollError = truncate(m);
                wait = backoffMs;
            }
            if (!sleep(wait)) return;
        }
    }

    private boolean sleep(long ms) {
        try {
            Thread.sleep(ms);
            return true;
        } catch (InterruptedException e) {
            return false;
        }
    }

    // ---- is calistirma --------------------------------------------------------

    /** Is alindi (Grid'de "running"): ne olursa olsun bir sonuc yollanir. */
    private void run(JsonNode job) throws InterruptedException {
        JsonNode idNode = job.path("id");
        if (!idNode.canConvertToLong()) {
            LOG.warning(() -> "sa_job_invalid (id yok) " + truncate(job.toString()));
            return;
        }
        long id = idNode.asLong();
        String kind = job.path("kind").asText("");
        LOG.info(() -> "sa_job_start id=" + id + " tur=" + kind);
        long t0 = System.nanoTime();
        ObjectNode body = JSON.createObjectNode();
        try {
            if ("iec61850_discover".equals(kind)) {
                ObjectNode result = discover(job.path("params"));
                body.put("ok", true);
                body.set("result", result);
            } else {
                body.put("ok", false);
                body.put("error", truncate("desteklenmeyen is turu: " + kind));
            }
        } catch (Exception e) {
            body.removeAll();
            body.put("ok", false);
            body.put("error", errorText(e));
        }
        boolean ok = body.path("ok").asBoolean();
        String status = post(id, body);
        if ("ok".equals(status) && !ok) status = "error";
        lastId = id;
        lastStatus = status;
        lastAt = Instant.now();
        long ms = (System.nanoTime() - t0) / 1_000_000;
        String st = status;
        LOG.info(() -> "sa_job_done id=" + id + " tur=" + kind + " durum=" + st + " sure_ms=" + ms
                + (ok ? "" : " hata=" + body.path("error").asText()));
    }

    /** Sonucu yollar: "ok", "rejected" (404 / 409) ya da "post_failed". */
    private String post(long id, ObjectNode body) throws InterruptedException {
        for (int attempt = 0; ; attempt++) {
            try {
                int c = grid.postJobResult(id, body);
                if (c == 200) return "ok";
                LOG.warning(() -> "sa_job_result_rejected id=" + id + " http=" + c
                        + (c == 404 ? " (is bilinmiyor / suresi doldu)" : " (is zaten bitti)"));
                return "rejected";
            } catch (GridClient.GridHttpException e) {
                // 4xx: tekrar denemek anlamsiz; 5xx gecici olabilir.
                if (e.status < 500 || attempt >= POST_RETRY_MS.length) {
                    LOG.warning(() -> "sa_job_result_failed id=" + id + " " + e.getMessage());
                    return "post_failed";
                }
            } catch (IOException e) {
                if (attempt >= POST_RETRY_MS.length) {
                    LOG.warning(() -> "sa_job_result_failed id=" + id + " " + describe(e));
                    return "post_failed";
                }
            }
            if (stopped) return "post_failed";
            Thread.sleep(POST_RETRY_MS[attempt]);
        }
    }

    private static ObjectNode discover(JsonNode p) throws IOException, ServiceError {
        return discoveryResult(Iec61850Client.discover(settings(p)), MAX_NODES);
    }

    /** Sozlesme: params = {host, port (102), ap_title, ae_qualifier, timeout_ms (10000)}; null = varsayilan. */
    static Iec61850Client.Settings settings(JsonNode p) {
        JsonNode host = p.path("host");
        JsonNode port = p.path("port");
        JsonNode ap = p.path("ap_title");
        JsonNode ae = p.path("ae_qualifier");
        JsonNode to = p.path("timeout_ms");
        return Iec61850Client.Settings.of(host.isTextual() ? host.asText().trim() : "",
                        port.isIntegralNumber() ? port.asInt() : 102)
                .withAcse(ap.isTextual() ? Iec61850Client.Settings.parseApTitle(ap.asText()) : null,
                        ae.isIntegralNumber() ? Integer.valueOf(ae.asInt()) : null)
                .withTimeouts(to.isIntegralNumber() ? to.asInt() : 10_000, 30_000);
    }

    /**
     * Kesif sonucu (Grid sozlesmesi). {@code nodes}: FC'si ST ya da MX olan
     * yaprak (temel) oznitelikler, bean referansi ve tipiyle. {@code datasets}
     * ve RCB {@code dataset}'i noktali bicimde; RCB adresi Grid biciminde.
     */
    static ObjectNode discoveryResult(ServerModel model, int maxNodes) {
        ObjectNode r = JSON.createObjectNode();
        ArrayNode lds = r.putArray("logical_devices");
        ArrayNode nodes = r.putArray("nodes");
        boolean truncated = false;
        for (ModelNode ld : model.getChildren()) {
            lds.add(ld.getName());
            for (ModelNode ln : ld.getChildren()) {
                for (ModelNode d : ln.getChildren()) {
                    if (!(d instanceof FcModelNode f) || (f.getFc() != Fc.ST && f.getFc() != Fc.MX)) continue;
                    for (BasicDataAttribute b : f.getBasicDataAttributes()) {
                        if (nodes.size() >= maxNodes) {
                            truncated = true;
                            break;
                        }
                        ObjectNode n = nodes.addObject();
                        n.put("ref", b.getReference().toString());
                        n.put("fc", b.getFc().name());
                        n.put("type", b.getBasicType().name());
                    }
                }
            }
        }
        ArrayNode dss = r.putArray("datasets");
        Map<String, DataSet> sets = new TreeMap<>();
        for (DataSet d : model.getDataSets()) sets.put(d.getReferenceStr(), d);
        for (DataSet d : sets.values()) {
            ObjectNode o = dss.addObject();
            o.put("ref", d.getReferenceStr());
            ArrayNode members = o.putArray("members");
            for (FcModelNode m : d.getMembers()) {
                members.addObject().put("ref", m.getReference().toString()).put("fc", m.getFc().name());
            }
        }
        ArrayNode rcbs = r.putArray("rcbs");
        Map<String, Rcb> byRef = new TreeMap<>();
        for (Rcb b : model.getBrcbs()) byRef.put(RcbRef.of(b).gridString(), b);
        for (Rcb b : model.getUrcbs()) byRef.put(RcbRef.of(b).gridString(), b);
        for (Map.Entry<String, Rcb> e : byRef.entrySet()) {
            Rcb b = e.getValue();
            ObjectNode o = rcbs.addObject();
            o.put("ref", e.getKey());
            o.put("buffered", b instanceof Brcb);
            String ds = b.getDatSet() == null ? null : b.getDatSet().getStringValue();
            if (ds == null || ds.isEmpty()) o.putNull("dataset");
            else o.put("dataset", ds.replace('$', '.'));
            o.put("enabled", b.getRptEna() != null && b.getRptEna().getValue());
        }
        r.put("truncated", truncated);
        return r;
    }

    /** Kisa hata ozeti (en fazla 300 karakter): tur + mesaj. */
    static String errorText(Exception e) {
        String prefix;
        if (e instanceof ConnectException || e instanceof UnknownHostException
                || e instanceof NoRouteToHostException || e instanceof SocketTimeoutException) {
            prefix = "baglanti kurulamadi: ";
        } else if (e instanceof IOException) {
            prefix = "ag hatasi: ";
        } else if (e instanceof ServiceError se) {
            prefix = "IED hatasi (kod " + se.getErrorCode() + "): ";
        } else if (e instanceof IllegalArgumentException) {
            return truncate("gecersiz parametre: " + e.getMessage());
        } else {
            prefix = "beklenmeyen hata: ";
        }
        return truncate(prefix + describe(e));
    }

    private static String describe(Throwable e) {
        String m = e.getMessage();
        if (m == null && e.getCause() != null) m = e.getCause().toString();
        return e.getClass().getSimpleName() + (m == null ? "" : ": " + m);
    }

    private static String truncate(String s) {
        return s.length() > MAX_ERROR ? s.substring(0, MAX_ERROR - 3) + "..." : s;
    }
}

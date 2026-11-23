package com.enerjione.sagateway.iec61850;

import com.beanit.iec61850bean.Fc;
import com.beanit.iec61850bean.ServerModel;
import com.enerjione.sagateway.gateway.IedHandler;
import com.enerjione.sagateway.grid.GridClient.ValueItem;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.logging.Logger;
import java.util.regex.Pattern;

/**
 * Grid'deki bir IEC 61850 IED'si: istemciyi calistirir, gelen yapraklari
 * (nesne referansi + FC) Grid noktasina esler ve nokta tipine gore cevirir.
 *
 * <p>Eslesme kurali: ayni (ref, fc) birden cok noktaya gidebilir. {@code txt}
 * noktalar hic izlenmez (Grid gateway'den metin kabul etmez).
 */
public final class Iec61850Ied implements IedHandler {

    private static final Logger LOG = Logger.getLogger(Iec61850Ied.class.getName());
    private static final Pattern REF = Pattern.compile(
            "^[A-Za-z0-9_]{1,64}/[A-Za-z0-9_]{1,32}(\\.[A-Za-z0-9_]{1,64}){1,6}$");

    /** Grid noktasi: id + tip (sp/dp/mv/it/st/txt) + adres (ref, fc). */
    public record PointRef(long id, String code, String pointType, String ref, String fc) {
    }

    private final long iedId;
    private final String code;
    private final String fingerprint;
    private final Consumer<ValueItem> sink;
    private final Map<String, List<PointRef>> byKey = new LinkedHashMap<>();
    private final int pointCount;
    private final Iec61850Client client;
    // asagidakiler istemcinin kilidi altinda (onSample / onProblem) ya da tek is parcaciginda
    private final Set<Long> mismatchLogged = new HashSet<>();
    private final Set<String> notFoundLogged = new HashSet<>();
    private final Set<String> invalidSent = new HashSet<>();
    private volatile boolean down;

    public Iec61850Ied(long iedId, String code, String fingerprint, Iec61850Client.Settings settings,
                       List<PointRef> points, Consumer<ValueItem> sink) {
        this.iedId = iedId;
        this.code = code;
        this.fingerprint = fingerprint;
        this.sink = sink;
        List<Iec61850Client.Target> targets = new ArrayList<>();
        int n = 0;
        for (PointRef p : points) {
            if ("txt".equals(p.pointType())) continue; // metin Grid'e gonderilmez
            Fc fc = p.fc() == null ? null : Fc.fromString(p.fc().trim().toUpperCase());
            // Fc.fromString bilinmeyende null doner; null FC LN seviyesinde HER FC'ye uyar, bu yuzden atlanir.
            if (fc == null || p.ref() == null || !REF.matcher(p.ref()).matches()) {
                LOG.warning(() -> "sa_point_address_invalid ied=" + code + " nokta=" + p.code() + " ref=" + p.ref()
                        + " fc=" + p.fc() + " (atlanir)");
                continue;
            }
            Iec61850Client.Target t = new Iec61850Client.Target(p.ref(), fc);
            List<PointRef> l = byKey.get(t.key());
            if (l == null) {
                l = new ArrayList<>();
                byKey.put(t.key(), l);
                targets.add(t);
            }
            l.add(p);
            n++;
        }
        this.pointCount = n;
        if (settings.rcbs().isEmpty() && settings.pollMs() == 0) {
            LOG.warning(() -> "sa_iec61850_no_updates ied=" + code + " (rapor blogu yok, poll_fallback_ms=0: "
                    + "degerler yalniz baglanti kurulunca okunur)");
        }
        this.client = new Iec61850Client(code, settings, targets, new Listener());
    }

    public long iedId() {
        return iedId;
    }

    @Override
    public String code() {
        return code;
    }

    @Override
    public String protocol() {
        return "iec61850";
    }

    @Override
    public String fingerprint() {
        return fingerprint;
    }

    @Override
    public int pointCount() {
        return pointCount;
    }

    @Override
    public void start() {
        client.start();
    }

    /** Testler icin: mesgul RCB yeniden deneme araligi (start'tan once). */
    void rcbRetryMs(long ms) {
        client.rcbRetryMs(ms);
    }

    @Override
    public Status status() {
        Iec61850Client.Status st = client.status();
        return new Status(st.connected(), st.host(), st.reports(), st.reconnects(), st.lastError(), st.rcbEnabled(),
                st.pollMs());
    }

    @Override
    public void close() {
        client.close();
    }

    /** Baglanti yok: tum noktalar gecersiz. Yalniz duruma GECISTE (Iec104Ied ile ayni). */
    private void markDown(String reason) {
        if (down) return;
        down = true;
        LOG.warning(() -> "sa_ied_down ied=" + code + " sebep=" + reason + " nokta=" + pointCount);
        for (List<PointRef> l : byKey.values()) {
            for (PointRef p : l) sink.accept(new ValueItem(p.id(), null, "invalid", null, "comm_lost"));
        }
    }

    private final class Listener implements Iec61850Client.Listener {
        @Override
        public void onConnected(String host, ServerModel model) {
            down = false;
        }

        @Override
        public void onDisconnected(String reason) {
            markDown(reason);
        }

        @Override
        public void onConnectFailed(String host, String reason) {
            markDown(reason);
        }

        @Override
        public void onProblem(Iec61850Client.Target target, Iec61850Client.Problem problem, String detail) {
            List<PointRef> refs = byKey.get(target.key());
            if (refs == null) return;
            if (problem == Iec61850Client.Problem.NOT_LEAF) {
                for (PointRef p : refs) {
                    if (mismatchLogged.add(p.id())) {
                        LOG.warning(() -> "sa_point_type_mismatch ied=" + code + " ref=" + target.ref() + " fc="
                                + target.fc() + " nokta=" + p.code() + " (" + p.pointType() + ") yaprak degil: "
                                + detail + " atlanir");
                    }
                }
                return;
            }
            // Bulunamayan / okunamayan: bir kez gunluk, bir kez gecersiz.
            if (notFoundLogged.add(target.key())) {
                LOG.warning(() -> (problem == Iec61850Client.Problem.NOT_FOUND ? "sa_point_not_found" : "sa_point_read_failed")
                        + " ied=" + code + " ref=" + target.ref() + " fc=" + target.fc() + " nokta="
                        + refs.stream().map(PointRef::code).toList() + " " + detail);
            }
            if (invalidSent.add(target.key())) {
                for (PointRef p : refs) sink.accept(new ValueItem(p.id(), null, "invalid", null, "interrogation"));
            }
        }

        @Override
        public void onSample(Iec61850Client.Sample s) {
            List<PointRef> refs = byKey.get(s.target().key());
            if (refs == null) return;
            invalidSent.remove(s.target().key()); // tekrar kaybolursa yine bildirilsin
            String ts = s.time() == null ? null : s.time().toString();
            for (PointRef p : refs) {
                Double v = Iec61850Values.toPointValue(s.leaf(), p.pointType());
                if (v == null) {
                    if (mismatchLogged.add(p.id())) {
                        LOG.warning(() -> "sa_point_type_mismatch ied=" + code + " ref=" + s.target().ref() + " fc="
                                + s.target().fc() + " tip=" + s.leaf().getBasicType() + " nokta=" + p.code()
                                + " (" + p.pointType() + ") atlanir");
                    }
                    continue;
                }
                // NaN / sonsuz: Grid sayi olarak kabul etmez (104 ile ayni kural).
                boolean finite = Double.isFinite(v);
                sink.accept(new ValueItem(
                        p.id(),
                        finite ? v : null,
                        finite && s.good() ? "good" : "invalid",
                        ts,
                        s.cause()));
            }
        }
    }
}

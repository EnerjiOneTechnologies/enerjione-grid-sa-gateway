package com.enerjione.sagateway.gateway;

import com.enerjione.sagateway.grid.GridClient.ValueItem;
import com.enerjione.sagateway.iec104.Asdu;
import com.enerjione.sagateway.iec104.Iec104Client;
import com.enerjione.sagateway.iec104.Iec104Settings;
import com.enerjione.sagateway.iec104.InfoObject;
import com.enerjione.sagateway.iec104.TypeId;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.logging.Logger;

/**
 * Grid'deki bir IEC 104 IED'si: istemciyi calistirir, gelen nesneleri
 * (CA, IOA) uzerinden Grid noktasina esler.
 *
 * <p>Eslemede tip KODU degil tip AILESI bakilir: istasyonlar genel sorguya
 * cogunlukla zamansiz (M_DP_NA_1), kendiliginden degisime zamanli
 * (M_DP_TB_1) tiple yanit verir; ikisi de ayni noktadir.
 */
final class Iec104Ied implements IedHandler {

    private static final Logger LOG = Logger.getLogger(Iec104Ied.class.getName());

    /** Grid noktasi: id + tip (sp/dp/st/mv/it). */
    record PointRef(long id, String code, String pointType) {
    }

    final long iedId;
    private final String code;
    private final String fingerprint;
    private final Iec104Settings settings;
    private final Map<Integer, List<PointRef>> byIoa;
    private final int pointCount;
    private final Consumer<ValueItem> sink;
    private final Iec104Client client;
    private final Set<Integer> mismatchLogged = new HashSet<>();
    private final Set<Integer> unknownLogged = new HashSet<>();
    private volatile boolean down;
    private volatile boolean foreignCaLogged;

    Iec104Ied(long iedId, String code, String fingerprint, Iec104Settings settings,
              Map<Integer, List<PointRef>> byIoa, Consumer<ValueItem> sink) {
        this.iedId = iedId;
        this.code = code;
        this.fingerprint = fingerprint;
        this.settings = settings;
        this.byIoa = Map.copyOf(byIoa);
        this.pointCount = byIoa.values().stream().mapToInt(List::size).sum();
        this.sink = sink;
        this.client = new Iec104Client(code, settings, new Listener());
    }

    @Override
    public String code() {
        return code;
    }

    @Override
    public String protocol() {
        return "iec104";
    }

    @Override
    public String fingerprint() {
        return fingerprint;
    }

    @Override
    public void start() {
        client.start();
    }

    @Override
    public Status status() {
        Iec104Client.Status st = client.status();
        return new Status(st.connected(), st.host(), st.asduCount(), st.reconnects(), st.lastError(), 0, -1);
    }

    @Override
    public int pointCount() {
        return pointCount;
    }

    @Override
    public void close() {
        client.close();
    }

    /** Ailenin Grid nokta tipine uyup uymadigi. */
    static boolean compatible(TypeId t, String pointType) {
        String expected = t.family.pointType;
        return expected != null && expected.equals(pointType);
    }

    /** Baglanti yok: tum noktalar gecersiz (deger korunur). Yalniz duruma GECISTE. */
    private void markDown(String reason) {
        if (down) return;
        down = true;
        LOG.warning(() -> "sa_ied_down ied=" + code + " sebep=" + reason + " nokta=" + pointCount);
        for (List<PointRef> l : byIoa.values()) {
            for (PointRef p : l) sink.accept(new ValueItem(p.id(), null, "invalid", null, "comm_lost"));
        }
    }

    private final class Listener implements Iec104Client.Listener {
        @Override
        public void onConnected(String host) {
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
        public void onAsdu(Asdu asdu) {
            if (asdu.commonAddress() != settings.commonAddress()) {
                if (!foreignCaLogged) {
                    foreignCaLogged = true;
                    LOG.warning(() -> "sa_ied_foreign_ca ied=" + code + " beklenen=" + settings.commonAddress()
                            + " gelen=" + asdu.commonAddress() + " (atlanir)");
                }
                return;
            }
            TypeId t = asdu.type();
            String cause = asdu.causeName();
            for (InfoObject o : asdu.objects()) {
                List<PointRef> refs = byIoa.get(o.ioa());
                if (refs == null) {
                    if (unknownLogged.size() < 1000 && unknownLogged.add(o.ioa())) {
                        LOG.fine(() -> "sa_ied_unmapped ied=" + code + " ioa=" + o.ioa() + " tip=" + t);
                    }
                    continue;
                }
                boolean timeOk = o.time() != null && (o.flags() & InfoObject.TIME_INVALID) == 0;
                for (PointRef p : refs) {
                    if (!compatible(t, p.pointType())) {
                        if (mismatchLogged.add(o.ioa())) {
                            LOG.warning(() -> "sa_point_type_mismatch ied=" + code + " ioa=" + o.ioa() + " tip=" + t
                                    + " nokta=" + p.code() + " (" + p.pointType() + ") atlanir");
                        }
                        continue;
                    }
                    // NaN / sonsuz (bozuk float olcum): Grid sayi olarak kabul etmez;
                    // reddedilen satir noktayi eski "iyi" degerde birakirdi.
                    boolean finite = Double.isFinite(o.value());
                    sink.accept(new ValueItem(
                            p.id(),
                            finite ? o.value() : null,
                            finite && o.good() ? "good" : "invalid",
                            timeOk ? o.time().toString() : null,
                            cause));
                }
            }
        }
    }
}

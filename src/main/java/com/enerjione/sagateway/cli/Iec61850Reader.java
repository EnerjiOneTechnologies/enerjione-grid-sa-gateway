package com.enerjione.sagateway.cli;

import com.beanit.iec61850bean.BdaBoolean;
import com.beanit.iec61850bean.BdaDoubleBitPos;
import com.beanit.iec61850bean.Brcb;
import com.beanit.iec61850bean.DataSet;
import com.beanit.iec61850bean.Fc;
import com.beanit.iec61850bean.FcModelNode;
import com.beanit.iec61850bean.ModelNode;
import com.beanit.iec61850bean.Rcb;
import com.beanit.iec61850bean.ServerModel;
import com.beanit.iec61850bean.ServiceError;
import com.enerjione.sagateway.iec61850.Iec61850Client;
import com.enerjione.sagateway.iec61850.Iec61850Values;
import com.enerjione.sagateway.iec61850.RcbRef;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.io.PrintStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Sahada kullanilan 61850 okuyucu. Grid'e hicbir sey gondermez.
 *
 * <pre>
 * sa-gateway iec61850 --host 10.0.0.5 [--port 102]                 modeli listeler (IP'den kesif)
 * sa-gateway iec61850 --host 10.0.0.5 --ref LD/LN.DO.DA --fc ST    degerleri geldikce yazar
 *                     [--ref ... --fc ...] [--rcb LD/LLN0.BR.ad ...] [--yoklama MS] [--gi-yok]
 *                     [--ap-title 1,1,999,1,1] [--ae-qualifier 12] [--sure SN] [--json]
 * </pre>
 *
 * <p>Tek {@code --fc} verilirse tum {@code --ref}'lere uygulanir; aksi halde her
 * {@code --ref} icin sirayla bir {@code --fc}.
 */
public final class Iec61850Reader {

    private static final ObjectMapper JSON = new ObjectMapper();

    private Iec61850Reader() {
    }

    public static int run(String[] argv, PrintStream out) throws InterruptedException {
        Args a = new Args(argv, 1, Set.of("json", "gi-yok"));
        boolean json = a.flag("json");
        List<RcbRef> rcbs = new ArrayList<>();
        for (String r : a.list("rcb")) rcbs.add(RcbRef.parse(r));
        String ae = a.str("ae-qualifier", null);
        Iec61850Client.Settings s = Iec61850Client.Settings.of(a.required("host"), a.integer("port", 102))
                .withAcse(Iec61850Client.Settings.parseApTitle(a.str("ap-title", null)),
                        ae == null ? null : a.integer("ae-qualifier", 12))
                .withRcbs(rcbs)
                .withGiOnConnect(!a.flag("gi-yok"))
                .withPollMs(a.integer("yoklama", 0));
        List<Iec61850Client.Target> targets = targets(a.list("ref"), a.list("fc"));
        if (targets.isEmpty()) return discover(s, out, json);
        return watch(s, targets, a.integer("sure", 0), out, json);
    }

    static List<Iec61850Client.Target> targets(List<String> refs, List<String> fcs) {
        if (refs.isEmpty()) return List.of();
        if (fcs.isEmpty()) throw new IllegalArgumentException("--ref icin --fc zorunlu (ST, MX, ...)");
        if (fcs.size() != 1 && fcs.size() != refs.size()) {
            throw new IllegalArgumentException("--fc sayisi 1 ya da --ref sayisi kadar olmali");
        }
        List<Iec61850Client.Target> l = new ArrayList<>();
        for (int i = 0; i < refs.size(); i++) {
            String f = (fcs.size() == 1 ? fcs.get(0) : fcs.get(i)).trim().toUpperCase();
            Fc fc = Fc.fromString(f);
            if (fc == null) throw new IllegalArgumentException("gecersiz FC: " + f);
            l.add(new Iec61850Client.Target(refs.get(i).trim(), fc));
        }
        return l;
    }

    // ---- kesif ------------------------------------------------------------------

    private static int discover(Iec61850Client.Settings s, PrintStream out, boolean json) {
        ServerModel model;
        try {
            model = Iec61850Client.discover(s);
        } catch (IOException | ServiceError e) {
            info(out, json, "baglanilamadi " + s.host() + ":" + s.port() + ": " + e);
            return 1;
        }
        info(out, json, "baglandi " + s.host() + ":" + s.port() + ", " + model.getChildren().size() + " LD");
        for (ModelNode ld : model.getChildren()) {
            if (!json) out.println("LD " + ld.getName());
            for (ModelNode ln : ld.getChildren()) {
                if (!json) out.println("  LN " + ln.getName());
                Map<String, List<String>> dos = new LinkedHashMap<>();
                for (ModelNode d : ln.getChildren()) {
                    if (d instanceof Rcb || !(d instanceof FcModelNode f)) continue;
                    dos.computeIfAbsent(d.getName(), k -> new ArrayList<>()).add(f.getFc().name());
                }
                for (Map.Entry<String, List<String>> e : dos.entrySet()) {
                    if (json) {
                        ObjectNode n = JSON.createObjectNode();
                        n.put("ld", ld.getName());
                        n.put("ln", ln.getName());
                        n.put("do", e.getKey());
                        ArrayNode fcs = n.putArray("fc");
                        e.getValue().forEach(fcs::add);
                        out.println(n);
                    } else {
                        out.println("    " + e.getKey() + "  " + e.getValue());
                    }
                }
            }
        }
        if (!json) out.println("# veri kumeleri");
        Map<String, DataSet> sets = new TreeMap<>();
        for (DataSet d : model.getDataSets()) sets.put(d.getReferenceStr(), d);
        for (DataSet d : sets.values()) {
            List<String> members = new ArrayList<>();
            for (FcModelNode m : d.getMembers()) members.add(m.getReference() + " [" + m.getFc() + "]");
            if (json) {
                ObjectNode n = JSON.createObjectNode();
                n.put("dataset", d.getReferenceStr());
                ArrayNode arr = n.putArray("members");
                members.forEach(arr::add);
                out.println(n);
            } else {
                out.println(d.getReferenceStr() + "  (" + members.size() + " uye)");
                for (String m : members) out.println("    " + m);
            }
        }
        if (!json) out.println("# rapor bloklari (Grid bicimi)");
        Map<String, Rcb> rcbs = new TreeMap<>();
        for (Rcb r : model.getBrcbs()) rcbs.put(r.getReference().toString(), r);
        for (Rcb r : model.getUrcbs()) rcbs.put(r.getReference().toString(), r);
        for (Rcb r : rcbs.values()) {
            String ref = r.getReference().toString();
            int dot = ref.lastIndexOf('.');
            boolean buffered = r instanceof Brcb;
            String grid = ref.substring(0, dot) + (buffered ? ".BR." : ".RP.") + ref.substring(dot + 1);
            String ds = r.getDatSet() == null ? "" : r.getDatSet().getStringValue();
            boolean ena = r.getRptEna() != null && r.getRptEna().getValue();
            if (json) {
                ObjectNode n = JSON.createObjectNode();
                n.put("rcb", grid);
                n.put("buffered", buffered);
                n.put("dataset", ds);
                n.put("enabled", ena);
                out.println(n);
            } else {
                out.println(grid + "  veri_kumesi=" + (ds == null || ds.isEmpty() ? "?" : ds)
                        + (ena ? "  (su an baska istemcide etkin)" : ""));
            }
        }
        out.flush();
        return 0;
    }

    // ---- izleme -----------------------------------------------------------------

    private static int watch(Iec61850Client.Settings s, List<Iec61850Client.Target> targets, int seconds,
                             PrintStream out, boolean json) throws InterruptedException {
        Map<String, Integer> counts = new TreeMap<>();
        CountDownLatch done = new CountDownLatch(1);
        Iec61850Client client = new Iec61850Client("okuyucu", s, targets, new Iec61850Client.Listener() {
            @Override
            public void onConnected(String host, ServerModel model) {
                info(out, json, "baglandi " + host + ":" + s.port() + ", model okundu (" + model.getChildren().size()
                        + " LD)");
            }

            @Override
            public void onDisconnected(String reason) {
                info(out, json, "baglanti koptu: " + reason);
            }

            @Override
            public void onConnectFailed(String host, String reason) {
                info(out, json, "baglanilamadi " + host + ":" + s.port() + ": " + reason);
            }

            @Override
            public void onProblem(Iec61850Client.Target t, Iec61850Client.Problem p, String detail) {
                info(out, json, t.ref() + " [" + t.fc() + "] kullanilamiyor: " + p + " " + detail);
            }

            @Override
            public void onSample(Iec61850Client.Sample x) {
                synchronized (counts) {
                    counts.merge(x.cause(), 1, Integer::sum);
                }
                out.println(json ? jsonLine(x) : textLine(x));
                out.flush();
            }
        });
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            client.close();
            done.countDown();
        }));
        client.start();
        if (seconds > 0) {
            done.await(seconds, TimeUnit.SECONDS);
        } else {
            done.await();
        }
        Iec61850Client.Status st = client.status();
        client.close();
        synchronized (counts) {
            info(out, json, "ozet: " + counts + " rapor=" + st.reports() + " etkin_rcb=" + st.rcbEnabled());
        }
        return 0;
    }

    static String textLine(Iec61850Client.Sample x) {
        StringBuilder sb = new StringBuilder();
        Double num = Iec61850Values.numeric(x.leaf());
        sb.append(Instant.now()).append("  ").append(x.target().ref()).append(" [").append(x.target().fc()).append(']')
                .append("  ").append(x.leaf().getBasicType())
                .append("  ").append(x.cause())
                .append("  deger ").append(x.leaf().getValueString());
        // Sayisal karsilik yalniz metin degerli tiplerde (ON/OFF, true/false) eklenir.
        if (num != null && (x.leaf() instanceof BdaDoubleBitPos || x.leaf() instanceof BdaBoolean)) {
            sb.append(" (").append(format(num)).append(')');
        }
        sb.append("  kalite ").append(x.good() ? "iyi" : "GECERSIZ (" + x.validity() + ")");
        if (x.time() != null) sb.append("  zaman ").append(x.time());
        return sb.toString();
    }

    static String jsonLine(Iec61850Client.Sample x) {
        ObjectNode n = JSON.createObjectNode();
        n.put("received", Instant.now().toString());
        n.put("ref", x.target().ref());
        n.put("fc", x.target().fc().name());
        n.put("type", String.valueOf(x.leaf().getBasicType()));
        n.put("cause", x.cause());
        n.put("value", x.leaf().getValueString());
        Double num = Iec61850Values.numeric(x.leaf());
        if (num == null || !Double.isFinite(num)) n.putNull("numeric");
        else n.put("numeric", num);
        n.put("good", x.good());
        if (x.validity() != null) n.put("validity", x.validity());
        if (x.time() != null) n.put("device_time", x.time().toString());
        return n.toString();
    }

    private static String format(double v) {
        if (v == Math.rint(v) && Math.abs(v) < 1e15) return Long.toString((long) v);
        return Double.toString(v);
    }

    private static void info(PrintStream out, boolean json, String msg) {
        if (json) {
            ObjectNode n = JSON.createObjectNode();
            n.put("info", msg);
            n.put("at", Instant.now().toString());
            out.println(n);
        } else {
            out.println("# " + msg);
        }
        out.flush();
    }
}

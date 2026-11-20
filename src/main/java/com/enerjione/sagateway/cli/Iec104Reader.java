package com.enerjione.sagateway.cli;

import com.enerjione.sagateway.iec104.Asdu;
import com.enerjione.sagateway.iec104.Iec104Client;
import com.enerjione.sagateway.iec104.Iec104Settings;
import com.enerjione.sagateway.iec104.InfoObject;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.PrintStream;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Sahada kullanilan 104 okuyucu: bir istasyona baglanir, genel sorgu yapar ve
 * gelen her bilgi nesnesini satir satir yazar. Grid'e hicbir sey gondermez.
 *
 * <pre>
 * sa-gateway iec104 --host 10.0.0.5 [--port 2404] [--ca 1] [--yedek 10.0.0.6]
 *                   [--gi-aralik 0] [--k 12 --w 8 --t0 30 --t1 15 --t2 10 --t3 20]
 *                   [--saat-dilimi UTC] [--sure 0] [--json]
 * </pre>
 */
public final class Iec104Reader {

    private static final ObjectMapper JSON = new ObjectMapper();

    private Iec104Reader() {
    }

    public static int run(String[] argv, PrintStream out) throws InterruptedException {
        Args a = new Args(argv, 1, Set.of("json"));
        Iec104Settings s = new Iec104Settings(
                a.required("host"),
                a.integer("port", 2404),
                a.str("yedek", null),
                a.integer("ca", 1),
                a.integer("oa", 0),
                a.integer("k", 12),
                a.integer("w", 8),
                a.integer("t0", 30),
                a.integer("t1", 15),
                a.integer("t2", 10),
                a.integer("t3", 20),
                a.integer("gi-aralik", 0),
                ZoneId.of(a.str("saat-dilimi", "UTC")));
        int seconds = a.integer("sure", 0);
        boolean json = a.flag("json");

        Map<String, Integer> counts = new TreeMap<>();
        CountDownLatch done = new CountDownLatch(1);
        Iec104Client client = new Iec104Client("okuyucu", s, new Iec104Client.Listener() {
            @Override
            public void onConnected(String host) {
                info(out, json, "baglandi " + host + ":" + s.port() + " CA " + s.commonAddress() + ", genel sorgu gonderildi");
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
            public void onInterrogationDone() {
                info(out, json, "genel sorgu tamamlandi");
            }

            @Override
            public void onAsdu(Asdu asdu) {
                synchronized (counts) {
                    counts.merge(asdu.typeName(), asdu.objects().size(), Integer::sum);
                }
                for (InfoObject o : asdu.objects()) {
                    out.println(json ? jsonLine(asdu, o) : textLine(asdu, o));
                }
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
        client.close();
        synchronized (counts) {
            info(out, json, "ozet: " + counts);
        }
        return 0;
    }

    static String textLine(Asdu asdu, InfoObject o) {
        StringBuilder sb = new StringBuilder();
        sb.append(Instant.now()).append("  CA ").append(asdu.commonAddress())
                .append("  IOA ").append(o.ioa())
                .append("  ").append(asdu.typeName())
                .append("  ").append(asdu.causeName())
                .append("  deger ").append(format(o.value()))
                .append("  kalite ").append(o.good() ? "iyi" : "GECERSIZ");
        if (o.time() != null) sb.append("  zaman ").append(o.time());
        String f = o.flagText();
        if (!f.isEmpty()) sb.append("  [").append(f).append(']');
        if (asdu.test()) sb.append("  (TEST)");
        return sb.toString();
    }

    static String jsonLine(Asdu asdu, InfoObject o) {
        ObjectNode n = JSON.createObjectNode();
        n.put("received", Instant.now().toString());
        n.put("ca", asdu.commonAddress());
        n.put("ioa", o.ioa());
        n.put("type", asdu.typeName());
        n.put("type_id", asdu.typeCode());
        n.put("cause", asdu.causeName());
        n.put("cot", asdu.cause());
        n.put("value", o.value());
        n.put("good", o.good());
        n.put("flags", o.flagText());
        if (o.time() != null) n.put("device_time", o.time().toString());
        n.put("test", asdu.test());
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

package com.enerjione.sagateway;

import com.enerjione.sagateway.cli.Iec104Reader;
import com.enerjione.sagateway.gateway.SaGateway;
import com.enerjione.sagateway.grid.GridClient;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneId;
import java.util.concurrent.CountDownLatch;
import java.util.logging.ConsoleHandler;
import java.util.logging.Formatter;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

/**
 * Giris noktasi.
 *
 * <pre>
 * sa-gateway iec104 --host ...   tek istasyonu oku, ekrana yaz (Grid'e gondermez)
 * sa-gateway calis               gateway modu (ortam degiskenleri, bkz. README)
 * sa-gateway surum
 * </pre>
 */
public final class Main {

    private Main() {
    }

    public static void main(String[] args) throws Exception {
        configureLogging(env("E1_LOG_LEVEL", "INFO"));
        String cmd = args.length == 0 ? "yardim" : args[0];
        int code;
        try {
            code = switch (cmd) {
                case "iec104" -> Iec104Reader.run(args, System.out);
                case "calis" -> runGateway();
                case "surum", "--version" -> {
                    System.out.println(version());
                    yield 0;
                }
                default -> {
                    usage();
                    yield cmd.equals("yardim") || cmd.equals("--help") ? 0 : 2;
                }
            };
        } catch (IllegalArgumentException e) {
            System.err.println("hata: " + e.getMessage());
            usage();
            code = 2;
        }
        System.exit(code);
    }

    private static int runGateway() throws IOException, InterruptedException {
        String url = required("E1_GRID_URL");
        String token = token();
        SaGateway.Options opt = new SaGateway.Options(
                Integer.parseInt(env("E1_CONFIG_REFRESH_SEC", "60")),
                Integer.parseInt(env("E1_BATCH_MS", "500")),
                Integer.parseInt(env("E1_BATCH_MAX", "1000")),
                Integer.parseInt(env("E1_QUEUE_MAX", "100000")),
                Integer.parseInt(env("E1_HEALTH_PORT", "8030")),
                ZoneId.of(env("E1_DEVICE_TIME_ZONE", "UTC")));
        Logger.getLogger("").info(() -> "sa_gateway_start surum=" + version() + " grid=" + url);
        SaGateway gw = new SaGateway(new GridClient(url, token), opt);
        CountDownLatch stop = new CountDownLatch(1);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            gw.close();
            stop.countDown();
        }));
        gw.start();
        stop.await();
        return 0;
    }

    private static String token() throws IOException {
        String file = System.getenv("E1_SA_TOKEN_FILE");
        if (file != null && !file.isBlank()) {
            return Files.readString(Path.of(file)).trim();
        }
        return required("E1_SA_TOKEN");
    }

    private static String required(String name) {
        String v = System.getenv(name);
        if (v == null || v.isBlank()) throw new IllegalArgumentException(name + " ortam degiskeni zorunlu");
        return v.trim();
    }

    private static String env(String name, String def) {
        String v = System.getenv(name);
        return v == null || v.isBlank() ? def : v.trim();
    }

    static String version() {
        String v = Main.class.getPackage().getImplementationVersion();
        return v == null ? "dev" : v;
    }

    private static void usage() {
        System.err.println("""
                kullanim:
                  sa-gateway iec104 --host IP [--port 2404] [--ca 1] [--yedek IP] [--gi-aralik SN]
                                    [--k 12 --w 8 --t0 30 --t1 15 --t2 10 --t3 20]
                                    [--saat-dilimi UTC] [--sure SN] [--json]
                  sa-gateway calis      (E1_GRID_URL, E1_SA_TOKEN[_FILE])
                  sa-gateway surum""");
    }

    private static void configureLogging(String level) {
        Logger root = Logger.getLogger("");
        for (var h : root.getHandlers()) root.removeHandler(h);
        ConsoleHandler h = new ConsoleHandler();
        Level lv = Level.parse(level.toUpperCase());
        h.setLevel(lv);
        h.setFormatter(new Formatter() {
            @Override
            public String format(LogRecord r) {
                String msg = r.getMessage();
                StringBuilder sb = new StringBuilder()
                        .append(java.time.Instant.ofEpochMilli(r.getMillis())).append(' ')
                        .append(r.getLevel().getName()).append(' ')
                        .append(msg);
                if (r.getThrown() != null) sb.append(" | ").append(r.getThrown());
                return sb.append(System.lineSeparator()).toString();
            }
        });
        root.addHandler(h);
        root.setLevel(lv);
    }
}

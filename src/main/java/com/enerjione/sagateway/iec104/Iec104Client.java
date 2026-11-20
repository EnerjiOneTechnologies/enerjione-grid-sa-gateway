package com.enerjione.sagateway.iec104;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * IEC 60870-5-104 istemcisi (kontrol istasyonu / master), YALNIZ izleme yonu.
 *
 * <p>Baglanir, STARTDT ile veri aktarimini baslatir, genel sorgu yapar,
 * kendiliginden gelen ASDU'lari dinleyiciye verir. t0-t3 ve k/w kurallari
 * standarttaki gibidir; ihlalde baglanti kapatilip yeniden kurulur. Yedek adres
 * varsa her basarisiz denemede adresler arasinda gecilir.
 *
 * <p>Tum protokol durumu tek bir is parcaciginda tutulur; dinleyici geri
 * cagirimlari da o is parcaciginda calisir (hizli donmeli).
 */
public final class Iec104Client implements AutoCloseable {

    private static final Logger LOG = Logger.getLogger(Iec104Client.class.getName());
    private static final int TICK_MS = 100;
    private static final long MAX_BACKOFF_MS = 30_000;

    /** Istemci olaylari. */
    public interface Listener {
        /** STARTDT onaylandi; veri akisi basladi. */
        default void onConnected(String host) {
        }

        /** Kurulmus bir baglanti koptu. */
        default void onDisconnected(String reason) {
        }

        /** TCP baglantisi kurulamadi. */
        default void onConnectFailed(String host, String reason) {
        }

        /** Genel sorgu tamamlandi (ACT_TERM). */
        default void onInterrogationDone() {
        }

        /** Izleme yonu ASDU'su (sistem komut onaylari buraya gelmez). */
        void onAsdu(Asdu asdu);
    }

    /** Anlik durum (saglik ucu ve gunluk icin). */
    public record Status(boolean connected, String host, long asduCount, long reconnects, String lastError) {
    }

    private final Iec104Settings s;
    private final Listener listener;
    private final AsduCodec codec;
    private final String name;

    private volatile boolean stopped;
    private volatile Socket socket;
    private Thread thread;

    private volatile boolean connected;
    private volatile String currentHost;
    private volatile long asduCount;
    private volatile long reconnects;
    private volatile String lastError = "";

    public Iec104Client(String name, Iec104Settings settings, Listener listener) {
        this(name, settings, AsduCodec.standard(settings.zone()), listener);
    }

    public Iec104Client(String name, Iec104Settings settings, AsduCodec codec, Listener listener) {
        this.name = name;
        this.s = settings;
        this.codec = codec;
        this.listener = listener;
    }

    public synchronized void start() {
        if (thread != null) return;
        thread = new Thread(this::runLoop, "iec104-" + name);
        thread.setDaemon(true);
        thread.start();
    }

    @Override
    public void close() {
        stopped = true;
        Socket sock = socket;
        if (sock != null) {
            try {
                sock.close();
            } catch (IOException ignored) {
                // kapanista hata onemsiz
            }
        }
        Thread t = thread;
        if (t != null) {
            t.interrupt();
            try {
                t.join(5_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    public Status status() {
        return new Status(connected, currentHost, asduCount, reconnects, lastError);
    }

    public Iec104Settings settings() {
        return s;
    }

    // ---- baglanti dongusu ----------------------------------------------------

    private void runLoop() {
        int attempt = 0;
        boolean primary = true;
        while (!stopped) {
            String host = (s.redundantHost() != null && !primary) ? s.redundantHost() : s.host();
            boolean established = false;
            try (Socket sock = new Socket()) {
                socket = sock;
                sock.connect(new InetSocketAddress(host, s.port()), s.t0() * 1000);
                sock.setTcpNoDelay(true);
                sock.setKeepAlive(true);
                sock.setSoTimeout(TICK_MS);
                currentHost = host;
                Session session = new Session(sock);
                String reason = session.run();
                established = session.started;
                note(reason);
                if (session.started) {
                    connected = false;
                    LOG.warning(() -> "iec104_link_down ied=" + name + " host=" + host + " sebep=" + reason);
                    listener.onDisconnected(reason);
                } else if (!stopped) {
                    LOG.warning(() -> "iec104_start_failed ied=" + name + " host=" + host + " sebep=" + reason);
                    listener.onConnectFailed(host, reason);
                }
            } catch (IOException e) {
                if (stopped) break;
                String reason = e.getClass().getSimpleName() + ": " + e.getMessage();
                note(reason);
                established = connected;
                if (connected) {
                    connected = false;
                    listener.onDisconnected(reason);
                } else {
                    LOG.fine(() -> "iec104_connect_failed ied=" + name + " host=" + host + " " + reason);
                    listener.onConnectFailed(host, reason);
                }
            } catch (RuntimeException e) {
                // Dinleyici hatasi baglanti dongusunu oldurmemeli.
                LOG.log(Level.SEVERE, "iec104_unexpected ied=" + name, e);
                note(e.toString());
                if (connected) {
                    connected = false;
                    listener.onDisconnected(e.toString());
                }
            } finally {
                socket = null;
            }
            if (stopped) break;
            reconnects++;
            if (established) attempt = 0;
            if (s.redundantHost() != null) primary = !primary;
            long delay = Math.min(MAX_BACKOFF_MS, 1000L << Math.min(attempt, 5));
            attempt++;
            sleepQuietly(delay);
        }
        connected = false;
    }

    private void note(String reason) {
        lastError = reason == null ? "" : reason;
    }

    private void sleepQuietly(long ms) {
        long end = System.nanoTime() + ms * 1_000_000;
        while (!stopped && System.nanoTime() < end) {
            try {
                Thread.sleep(Math.min(200, Math.max(1, (end - System.nanoTime()) / 1_000_000)));
            } catch (InterruptedException e) {
                return;
            }
        }
    }

    // ---- tek baglanti oturumu -----------------------------------------------

    private final class Session {
        private final InputStream in;
        private final OutputStream out;
        private final byte[] buf = new byte[4096];
        private int bufLen;

        private int vs;
        private int vr;
        private int ackedByPeer;
        private final ArrayDeque<Long> sentITimes = new ArrayDeque<>();
        private int unackedRecv;
        private long firstUnackedRecvAt;
        private long lastRecvAt;
        private long startdtSentAt = -1;
        private long testfrSentAt = -1;
        private boolean started;
        private boolean giDue;
        private boolean giPending;
        private long lastGiAt;
        private final Set<Integer> unsupportedLogged = new HashSet<>();

        Session(Socket sock) throws IOException {
            this.in = sock.getInputStream();
            this.out = sock.getOutputStream();
        }

        /** Oturum bitene kadar calisir; bitis sebebini doner. */
        String run() throws IOException {
            long now = now();
            lastRecvAt = now;
            write(Apci.u(Apci.STARTDT_ACT));
            startdtSentAt = now;
            while (!stopped) {
                int n;
                try {
                    n = in.read(buf, bufLen, buf.length - bufLen);
                } catch (SocketTimeoutException e) {
                    n = 0;
                }
                if (n < 0) return "baglanti karsi taraftan kapatildi";
                bufLen += n;
                String err = drainFrames();
                if (err != null) return err;
                err = timers(now());
                if (err != null) return err;
            }
            return "durduruldu";
        }

        private String drainFrames() throws IOException {
            int off = 0;
            String err = null;
            while (bufLen - off >= 2) {
                if ((buf[off] & 0xFF) != Apci.START) {
                    err = String.format("gecersiz baslangic bayti 0x%02X", buf[off] & 0xFF);
                    break;
                }
                int len = buf[off + 1] & 0xFF;
                if (len < 4 || len > Apci.MAX_LENGTH) {
                    err = "gecersiz APDU boyu " + len;
                    break;
                }
                if (bufLen - off < len + 2) break;
                Apci.Frame f = Apci.parse(buf, off, len);
                off += len + 2;
                err = handle(f);
                if (err != null) break;
            }
            if (off > 0) {
                System.arraycopy(buf, off, buf, 0, bufLen - off);
                bufLen -= off;
            }
            return err;
        }

        private String handle(Apci.Frame f) throws IOException {
            long now = now();
            lastRecvAt = now;
            switch (f.kind()) {
                case I -> {
                    if (f.sendSeq() != vr) {
                        return "sira hatasi: N(S)=" + f.sendSeq() + " beklenen=" + vr;
                    }
                    vr = (vr + 1) % Apci.MODULO;
                    if (unackedRecv == 0) firstUnackedRecvAt = now;
                    unackedRecv++;
                    String err = ack(f.recvSeq());
                    if (err != null) return err;
                    onAsduBytes(f.asdu());
                    if (unackedRecv >= s.w()) sendS();
                }
                case S -> {
                    return ack(f.recvSeq());
                }
                case U -> {
                    switch (f.function()) {
                        case Apci.STARTDT_CON -> {
                            if (!started) {
                                started = true;
                                startdtSentAt = -1;
                                connected = true;
                                LOG.info(() -> "iec104_started ied=" + name + " host=" + currentHost
                                        + " ca=" + s.commonAddress());
                                listener.onConnected(currentHost);
                                sendGi();
                            }
                        }
                        case Apci.TESTFR_ACT -> write(Apci.u(Apci.TESTFR_CON));
                        case Apci.TESTFR_CON -> testfrSentAt = -1;
                        default -> {
                            // STOPDT_CON vb.: izleme istemcisinde islem yok
                        }
                    }
                }
            }
            return null;
        }

        private String ack(int nr) {
            int outstanding = Apci.distance(ackedByPeer, vs);
            int d = Apci.distance(ackedByPeer, nr);
            if (d > outstanding) {
                return "gecersiz N(R)=" + nr + " (gonderilen=" + vs + ")";
            }
            for (int i = 0; i < d; i++) sentITimes.pollFirst();
            ackedByPeer = nr;
            return null;
        }

        private void onAsduBytes(byte[] asduBytes) {
            Asdu asdu;
            try {
                asdu = codec.decode(asduBytes, 0, asduBytes.length);
            } catch (AsduCodec.MalformedAsduException e) {
                LOG.warning(() -> "iec104_bad_asdu ied=" + name + " " + e.getMessage());
                return;
            }
            asduCount++;
            TypeId t = asdu.type();
            if (t == null) {
                if (unsupportedLogged.add(asdu.typeCode())) {
                    LOG.warning(() -> "iec104_unsupported_type ied=" + name + " tip=" + asdu.typeCode()
                            + " (bu tip atlanir)");
                }
                return;
            }
            switch (t) {
                case C_IC_NA_1 -> {
                    if (asdu.cause() == Asdu.COT_ACTIVATION_CON && asdu.negative()) {
                        giPending = false;
                        LOG.warning(() -> "iec104_gi_rejected ied=" + name);
                    } else if (asdu.cause() == Asdu.COT_ACTIVATION_TERM) {
                        giPending = false;
                        LOG.fine(() -> "iec104_gi_done ied=" + name);
                        listener.onInterrogationDone();
                    }
                }
                case C_CI_NA_1, C_CS_NA_1 -> {
                    // onaylar: izleme istemcisinde islem yok
                }
                case M_EI_NA_1 -> {
                    // Istasyon yeniden basladi: tum durumu yeniden al.
                    LOG.info(() -> "iec104_station_init ied=" + name);
                    giDue = true;
                }
                default -> listener.onAsdu(asdu);
            }
        }

        private String timers(long now) throws IOException {
            long t1 = s.t1() * 1_000_000_000L;
            if (startdtSentAt >= 0 && now - startdtSentAt >= t1) return "STARTDT onayi gelmedi (t1)";
            if (testfrSentAt >= 0 && now - testfrSentAt >= t1) return "TESTFR onayi gelmedi (t1)";
            Long oldest = sentITimes.peekFirst();
            if (oldest != null && now - oldest >= t1) return "gonderilen cerceve onaylanmadi (t1)";
            if (unackedRecv > 0 && now - firstUnackedRecvAt >= s.t2() * 1_000_000_000L) sendS();
            if (testfrSentAt < 0 && now - lastRecvAt >= s.t3() * 1_000_000_000L) {
                write(Apci.u(Apci.TESTFR_ACT));
                testfrSentAt = now;
            }
            if (started) {
                boolean periodic = s.giIntervalSec() > 0 && now - lastGiAt >= s.giIntervalSec() * 1_000_000_000L;
                if (giDue || periodic) sendGi();
            }
            return null;
        }

        private void sendGi() throws IOException {
            byte[] asdu = codec.interrogation(Asdu.COT_ACTIVATION, s.originatorAddress(), s.commonAddress(), 20);
            if (sendI(asdu)) {
                giPending = true;
                giDue = false;
                lastGiAt = now();
            } else {
                giDue = true; // k penceresi dolu: sonraki turda
            }
        }

        private boolean sendI(byte[] asdu) throws IOException {
            if (Apci.distance(ackedByPeer, vs) >= s.k()) return false;
            write(Apci.i(vs, vr, asdu));
            vs = (vs + 1) % Apci.MODULO;
            sentITimes.addLast(now());
            unackedRecv = 0; // I cercevesi N(R) ile alinanlari da onaylar
            return true;
        }

        private void sendS() throws IOException {
            write(Apci.s(vr));
            unackedRecv = 0;
        }

        private void write(byte[] frame) throws IOException {
            out.write(frame);
            out.flush();
        }

        private long now() {
            return System.nanoTime();
        }
    }
}

package com.enerjione.sagateway.iec104;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Testler icin kucuk bir 104 istasyonu (controlled station). Standart davranis:
 * STARTDT'ye onay, genel sorguya ACT_CON + veri + ACT_TERM, TESTFR'ye onay.
 * Hata senaryolari icin bayraklar vardir.
 */
public final class TestSlave implements AutoCloseable {

    public final ServerSocket server;
    public final int ca;
    public final AtomicInteger connections = new AtomicInteger();
    public final AtomicInteger interrogations = new AtomicInteger();
    /** Biten oturum sayisi (istemci kapatti ya da hat koptu). */
    public final AtomicInteger disconnects = new AtomicInteger();
    public final AtomicInteger testfrReceived = new AtomicInteger();
    final LinkedBlockingQueue<Integer> sFrames = new LinkedBlockingQueue<>();
    public final List<byte[]> giData = new CopyOnWriteArrayList<>();

    volatile boolean answerTestfr = true;
    volatile boolean answerStartdt = true;
    /** Ilk I cercevesini bu N(S) ile gonder (sira hatasi senaryosu). */
    volatile int firstSendSeqOverride = -1;

    private volatile Session current;
    private final Thread acceptor;
    private volatile boolean closed;

    public TestSlave(int ca) throws IOException {
        this.ca = ca;
        this.server = new ServerSocket(0, 5, InetAddress.getByName("127.0.0.1"));
        acceptor = new Thread(this::acceptLoop, "test-slave");
        acceptor.setDaemon(true);
        acceptor.start();
    }

    public int port() {
        return server.getLocalPort();
    }

    private void acceptLoop() {
        while (!closed) {
            try {
                Socket s = server.accept();
                connections.incrementAndGet();
                Session old = current;
                if (old != null) old.close();
                Session sess = new Session(s);
                current = sess;
                Thread t = new Thread(sess::run, "test-slave-session");
                t.setDaemon(true);
                t.start();
            } catch (IOException e) {
                return;
            }
        }
    }

    /** Bagli istemciye kendiliginden ASDU gonderir. */
    public void send(byte[] asdu) throws IOException {
        Session s = current;
        if (s == null) throw new IOException("bagli istemci yok");
        s.sendI(asdu);
    }

    /** Mevcut baglantiyi sert kapatir (hat kopmasi). */
    public void dropConnection() {
        Session s = current;
        if (s != null) s.close();
    }

    public boolean started() {
        Session s = current;
        return s != null && s.started;
    }

    boolean waitStarted(long ms) throws InterruptedException {
        long end = System.currentTimeMillis() + ms;
        while (System.currentTimeMillis() < end) {
            if (started()) return true;
            Thread.sleep(20);
        }
        return false;
    }

    Integer nextSFrame(long ms) throws InterruptedException {
        return sFrames.poll(ms, TimeUnit.MILLISECONDS);
    }

    @Override
    public void close() throws IOException {
        closed = true;
        dropConnection();
        server.close();
    }

    // ---- ASDU kurucular (istasyon yonu) -------------------------------------

    public static byte[] asdu(int type, int cause, int ca, int ioa, byte... element) {
        byte[] out = new byte[6 + 3 + element.length];
        out[0] = (byte) type;
        out[1] = 1;
        out[2] = (byte) cause;
        out[3] = 0;
        out[4] = (byte) ca;
        out[5] = (byte) (ca >> 8);
        out[6] = (byte) ioa;
        out[7] = (byte) (ioa >> 8);
        out[8] = (byte) (ioa >> 16);
        System.arraycopy(element, 0, out, 9, element.length);
        return out;
    }

    public static byte[] cp56(Instant t) {
        byte[] b = new byte[7];
        Cp56Time2a.encode(t, ZoneOffset.UTC, b, 0);
        return b;
    }

    public static byte[] concat(byte[] a, byte[] b) {
        byte[] out = new byte[a.length + b.length];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }

    // ---- oturum --------------------------------------------------------------

    private final class Session {
        final Socket sock;
        final InputStream in;
        final OutputStream out;
        int vs;
        volatile int vr;
        volatile boolean started;

        Session(Socket s) throws IOException {
            this.sock = s;
            this.in = s.getInputStream();
            this.out = s.getOutputStream();
            if (firstSendSeqOverride >= 0) vs = firstSendSeqOverride;
        }

        void run() {
            try {
                while (true) {
                    int start = in.read();
                    if (start < 0) return;
                    if (start != Apci.START) return;
                    int len = in.read();
                    byte[] body = in.readNBytes(len);
                    if (body.length < len) return;
                    byte[] frame = new byte[len + 2];
                    frame[0] = (byte) start;
                    frame[1] = (byte) len;
                    System.arraycopy(body, 0, frame, 2, len);
                    Apci.Frame f = Apci.parse(frame, 0, len);
                    switch (f.kind()) {
                        case U -> onU(f.function());
                        case S -> sFrames.add(f.recvSeq());
                        case I -> {
                            vr = (vr + 1) % Apci.MODULO;
                            onI(f.asdu());
                        }
                    }
                }
            } catch (IOException e) {
                // baglanti kapandi
            } finally {
                disconnects.incrementAndGet();
            }
        }

        private void onU(int fn) throws IOException {
            if (fn == Apci.STARTDT_ACT && answerStartdt) {
                write(Apci.u(Apci.STARTDT_CON));
                started = true;
            } else if (fn == Apci.TESTFR_ACT) {
                testfrReceived.incrementAndGet();
                if (answerTestfr) write(Apci.u(Apci.TESTFR_CON));
            }
        }

        private void onI(byte[] a) throws IOException {
            int type = a[0] & 0xFF;
            if (type == TypeId.C_IC_NA_1.code && (a[2] & 0x3F) == Asdu.COT_ACTIVATION) {
                interrogations.incrementAndGet();
                byte[] con = a.clone();
                con[2] = (byte) Asdu.COT_ACTIVATION_CON;
                sendI(con);
                for (byte[] d : giData) sendI(d);
                byte[] term = a.clone();
                term[2] = (byte) Asdu.COT_ACTIVATION_TERM;
                sendI(term);
            }
        }

        synchronized void sendI(byte[] asdu) throws IOException {
            write(Apci.i(vs, vr, asdu));
            vs = (vs + 1) % Apci.MODULO;
        }

        synchronized void write(byte[] b) throws IOException {
            out.write(b);
            out.flush();
        }

        void close() {
            try {
                sock.close();
            } catch (IOException ignored) {
                // test
            }
        }
    }
}

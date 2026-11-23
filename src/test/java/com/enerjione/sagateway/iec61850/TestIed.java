package com.enerjione.sagateway.iec61850;

import com.beanit.iec61850bean.BasicDataAttribute;
import com.beanit.iec61850bean.BdaBoolean;
import com.beanit.iec61850bean.BdaDoubleBitPos;
import com.beanit.iec61850bean.BdaFloat32;
import com.beanit.iec61850bean.BdaInt64;
import com.beanit.iec61850bean.BdaQuality;
import com.beanit.iec61850bean.BdaTimestamp;
import com.beanit.iec61850bean.ClientAssociation;
import com.beanit.iec61850bean.ClientEventListener;
import com.beanit.iec61850bean.ClientSap;
import com.beanit.iec61850bean.Fc;
import com.beanit.iec61850bean.Rcb;
import com.beanit.iec61850bean.Report;
import com.beanit.iec61850bean.SclParser;
import com.beanit.iec61850bean.ServerEventListener;
import com.beanit.iec61850bean.ServerModel;
import com.beanit.iec61850bean.ServerSap;
import com.beanit.iec61850bean.ServiceError;
import com.beanit.iec61850bean.Urcb;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Testler icin surec ici 61850 sunucusu: {@code e1test.icd}'den iec61850bean
 * ServerSap'i bos bir portta calistirir. Not: bean sunucusu yalniz tamponsuz
 * (URCB) rapor gonderir; BRCB etkinlesir ama rapor uretmez.
 */
public final class TestIed implements AutoCloseable {

    public static final String LD = "E1TESTLD0";
    public static final Instant T0 = Instant.parse("2026-11-20T10:00:00.250Z");

    private final int port;
    private volatile ServerSap sap;

    public TestIed() throws Exception {
        try (ServerSocket s = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            this.port = s.getLocalPort();
        }
        start();
    }

    public int port() {
        return port;
    }

    /** Sunucuyu (yeniden) baslatir; degerler baslangic durumuna doner. */
    public synchronized void start() throws Exception {
        ServerModel model;
        try (InputStream in = TestIed.class.getResourceAsStream("/iec61850/e1test.icd")) {
            model = SclParser.parse(in).get(0);
        }
        ServerSap s = new ServerSap(port, 0, InetAddress.getByName("127.0.0.1"), model, null);
        s.startListening(new ServerEventListener() {
            @Override
            public List<ServiceError> write(List<BasicDataAttribute> bdas) {
                return null;
            }

            @Override
            public void serverStoppedListening(ServerSap serverSap) {
                // test
            }
        });
        sap = s;
        setPos(BdaDoubleBitPos.DoubleBitPos.ON, BdaQuality.Validity.GOOD, T0);
        setPhsA(12.5f, BdaQuality.Validity.GOOD, T0);
        update(m -> {
            List<BasicDataAttribute> l = new ArrayList<>();
            BdaTimestamp t = (BdaTimestamp) node(m, "GGIO1.Ind1.t", Fc.ST);
            t.setInstant(T0);
            l.add(t);
            BdaBoolean ind = (BdaBoolean) node(m, "GGIO1.Ind1.stVal", Fc.ST);
            ind.setValue(true);
            l.add(ind);
            BdaInt64 wh = (BdaInt64) node(m, "MMTR1.TotWh.actVal", Fc.ST);
            wh.setValue(123456L);
            l.add(wh);
            BdaFloat32 w = (BdaFloat32) node(m, "MMXU1.TotW.mag.f", Fc.MX);
            w.setFloat(1000.5f);
            l.add(w);
            return l;
        });
    }

    public synchronized void stop() {
        ServerSap s = sap;
        if (s != null) s.stop();
        sap = null;
    }

    @Override
    public void close() {
        stop();
    }

    /** Model kopyasindan BDA. setValues mirror'a yazar, bu yuzden kopya sart. */
    public static BasicDataAttribute node(ServerModel m, String rel, Fc fc) {
        BasicDataAttribute b = (BasicDataAttribute) m.findModelNode(LD + "/" + rel, fc);
        if (b == null) throw new IllegalArgumentException("modelde yok: " + rel);
        return b;
    }

    public interface Change {
        List<BasicDataAttribute> apply(ServerModel copy);
    }

    public synchronized void update(Change c) {
        ServerSap s = sap;
        s.setValues(c.apply(s.getModelCopy()));
    }

    /** XCBR1.Pos: once t ve q, sonra stVal (rapor tetikleyicisi). */
    public void setPos(BdaDoubleBitPos.DoubleBitPos pos, BdaQuality.Validity v, Instant t) {
        update(m -> {
            BdaTimestamp ts = (BdaTimestamp) node(m, "XCBR1.Pos.t", Fc.ST);
            ts.setInstant(t);
            BdaQuality q = (BdaQuality) node(m, "XCBR1.Pos.q", Fc.ST);
            q.setValidity(v);
            BdaDoubleBitPos st = (BdaDoubleBitPos) node(m, "XCBR1.Pos.stVal", Fc.ST);
            st.setDoubleBitPos(pos);
            return List.of(ts, q, st);
        });
    }

    /**
     * Ikinci istemci (SCADA benzetimi): LLN0'daki bir RCB'yi tutar / birakir ve
     * sunucudaki durumunu (RptEna, URCB icin Resv) okur.
     */
    public static final class Client implements AutoCloseable {
        private final ClientAssociation a;
        private final ServerModel m;

        public Client(int port) throws Exception {
            ClientSap sap = new ClientSap();
            sap.setResponseTimeout(3000);
            a = sap.associate(InetAddress.getByName("127.0.0.1"), port, null, new ClientEventListener() {
                @Override
                public void newReport(Report report) {
                    // test
                }

                @Override
                public void associationClosed(IOException e) {
                    // test
                }
            });
            m = a.retrieveModel();
        }

        private Rcb rcb(String name) throws Exception {
            String ref = LD + "/LLN0." + name;
            Rcb r = m.getBrcb(ref);
            if (r == null) r = m.getUrcb(ref);
            if (r == null) throw new IllegalArgumentException("RCB yok: " + name);
            a.getRcbValues(r);
            return r;
        }

        public synchronized boolean enabled(String name) throws Exception {
            return rcb(name).getRptEna().getValue();
        }

        public synchronized boolean reserved(String name) throws Exception {
            return rcb(name) instanceof Urcb u && u.getResv().getValue();
        }

        /** Yalniz URCB rezervasyonu (etkinlestirmeden). */
        public synchronized void reserve(String name) throws Exception {
            a.reserveUrcb((Urcb) rcb(name));
        }

        public synchronized void enable(String name) throws Exception {
            Rcb r = rcb(name);
            if (r instanceof Urcb u) a.reserveUrcb(u);
            a.enableReporting(r);
        }

        public synchronized void release(String name) throws Exception {
            Rcb r = rcb(name);
            a.disableReporting(r);
            if (r instanceof Urcb u) a.cancelUrcbReservation(u);
        }

        @Override
        public void close() {
            a.close();
        }
    }

    /** MMXU1.A.phsA: once t ve q, sonra cVal.mag.f. */
    public void setPhsA(float f, BdaQuality.Validity v, Instant t) {
        update(m -> {
            BdaTimestamp ts = (BdaTimestamp) node(m, "MMXU1.A.phsA.t", Fc.MX);
            ts.setInstant(t);
            BdaQuality q = (BdaQuality) node(m, "MMXU1.A.phsA.q", Fc.MX);
            q.setValidity(v);
            BdaFloat32 mag = (BdaFloat32) node(m, "MMXU1.A.phsA.cVal.mag.f", Fc.MX);
            mag.setFloat(f);
            return List.of(ts, q, mag);
        });
    }
}

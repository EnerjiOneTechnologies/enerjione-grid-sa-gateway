package com.enerjione.sagateway.iec61850;

import com.beanit.iec61850bean.BasicDataAttribute;
import com.beanit.iec61850bean.BdaBoolean;
import com.beanit.iec61850bean.BdaDoubleBitPos;
import com.beanit.iec61850bean.BdaFloat32;
import com.beanit.iec61850bean.BdaInt64;
import com.beanit.iec61850bean.BdaQuality;
import com.beanit.iec61850bean.BdaTimestamp;
import com.beanit.iec61850bean.Fc;
import com.beanit.iec61850bean.SclParser;
import com.beanit.iec61850bean.ServerEventListener;
import com.beanit.iec61850bean.ServerModel;
import com.beanit.iec61850bean.ServerSap;
import com.beanit.iec61850bean.ServiceError;
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

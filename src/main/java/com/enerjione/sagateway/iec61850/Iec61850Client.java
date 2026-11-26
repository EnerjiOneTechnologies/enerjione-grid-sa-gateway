package com.enerjione.sagateway.iec61850;

import com.beanit.iec61850bean.BasicDataAttribute;
import com.beanit.iec61850bean.BdaOptFlds;
import com.beanit.iec61850bean.BdaQuality;
import com.beanit.iec61850bean.BdaReasonForInclusion;
import com.beanit.iec61850bean.BdaTimestamp;
import com.beanit.iec61850bean.BdaTriggerConditions;
import com.beanit.iec61850bean.Brcb;
import com.beanit.iec61850bean.ClientAssociation;
import com.beanit.iec61850bean.ClientEventListener;
import com.beanit.iec61850bean.ClientSap;
import com.beanit.iec61850bean.Fc;
import com.beanit.iec61850bean.FcDataObject;
import com.beanit.iec61850bean.FcModelNode;
import com.beanit.iec61850bean.LogicalNode;
import com.beanit.iec61850bean.ModelNode;
import com.beanit.iec61850bean.ObjectReference;
import com.beanit.iec61850bean.Rcb;
import com.beanit.iec61850bean.Report;
import com.beanit.iec61850bean.ServerModel;
import com.beanit.iec61850bean.ServiceError;
import com.beanit.iec61850bean.Urcb;
import java.io.IOException;
import java.net.InetAddress;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * IEC 61850 MMS istemcisi (iec61850bean uzerinde), YALNIZ izleme yonu.
 *
 * <p>Akis (her baglantida): iliskilendirme (association) -> {@code retrieveModel()}
 * -> izlenen referanslari modelde bulma -> rapor bloklarini (RCB) etkinlestirme ->
 * ilk okuma (genel sorgu yerine) -> istenirse RCB'lerde GI -> raporlari dinleme,
 * istenirse periyodik yoklama. Baglanti koparsa 1 sn'den 30 sn'ye kadar artan
 * beklemeyle yeniden kurulur.
 *
 * <p>Is parcacigi kurali: iec61850bean'in {@link ClientAssociation}'i ayni anda tek
 * istek tasir (tek invoke id). Tum istekler bu sinifin is parcaciginda yapilir.
 * Raporlar bean'in actigi ayri is parcaciklarinda gelir (paralel olabilir); onlar
 * istek YAPMAZ, yalniz yerel modeli gunceller. Yerel model ve dinleyiciye
 * bildirim tek kilit altindadir: Grid'e yazilma sirasi model guncelleme sirasidir.
 *
 * <p>Bilerek durdurmada ({@link #close()}) oturum is parcacigi tuttugu RCB'leri
 * birakir (RptEna=false, URCB rezervasyonu iptal), sonra iliskiyi kapatir: yeni
 * iliski ayni ornekleri hemen alabilir. Baska istemcide oldugu icin etkinlesmeyen
 * RCB'ler {@link #RCB_RETRY_MS} araligiyla yeniden denenir.
 */
public final class Iec61850Client implements AutoCloseable {

    private static final Logger LOG = Logger.getLogger(Iec61850Client.class.getName());
    private static final long MAX_BACKOFF_MS = 30_000;
    /** Mesgul (baska istemcide) RCB'nin yeniden deneme araligi. */
    static final long RCB_RETRY_MS = 30_000;
    /** Birakma istekleri icin yanit zaman asimi ve toplam sure siniri. */
    private static final int RELEASE_TIMEOUT_MS = 1000;
    private static final long RELEASE_BUDGET_MS = 3000;
    /** close(): oturumun RCB'leri birakmasi icin beklenen en uzun sure. */
    private static final long CLOSE_JOIN_MS = 5000;
    /** Bu kadar oturum ust uste LN okumasinda zaman asimiyla biterse IED'de LN okumasi kapanir. */
    private static final int LN_ZAMAN_ASIMI_SINIRI = 2;

    /**
     * Baglanti ayarlari. Alanlar Grid SA'daki IEC 61850 IED baglantisiyla ayni
     * anlamdadir.
     *
     * @param apTitle karsi tarafin (IED, called) AP title'i; null = bean varsayilani (1,1,999,1,1)
     * @param aeQualifier karsi tarafin AE qualifier'i; null = bean varsayilani (12)
     * @param pollMs 0 = periyodik yoklama yok
     * @param timeoutMs TCP baglanti ve istek yanit zaman asimi
     * @param keepAliveMs bu sure hic trafik olmazsa hattin canli oldugu kucuk bir okumayla denetlenir
     */
    public record Settings(String host, int port, int[] apTitle, Integer aeQualifier, List<RcbRef> rcbs,
                           boolean giOnConnect, int pollMs, int timeoutMs, int keepAliveMs) {

        public Settings {
            if (host == null || host.isBlank()) throw new IllegalArgumentException("host bos");
            if (port < 1 || port > 65535) throw new IllegalArgumentException("port 1..65535");
            if (pollMs < 0) throw new IllegalArgumentException("poll_fallback_ms >= 0");
            if (timeoutMs < 100) throw new IllegalArgumentException("zaman asimi >= 100 ms");
            if (keepAliveMs < 100) throw new IllegalArgumentException("canlilik araligi >= 100 ms");
            if (apTitle != null && apTitle.length < 2) throw new IllegalArgumentException("ap_title en az 2 bilesen");
            apTitle = apTitle == null ? null : apTitle.clone();
            rcbs = rcbs == null ? List.of() : List.copyOf(rcbs);
        }

        /** Varsayilanlar: GI acik, yoklama yok, 10 sn zaman asimi, 30 sn canlilik. */
        public static Settings of(String host, int port) {
            return new Settings(host, port, null, null, List.of(), true, 0, 10_000, 30_000);
        }

        public Settings withRcbs(List<RcbRef> r) {
            return new Settings(host, port, apTitle, aeQualifier, r, giOnConnect, pollMs, timeoutMs, keepAliveMs);
        }

        public Settings withPollMs(int ms) {
            return new Settings(host, port, apTitle, aeQualifier, rcbs, giOnConnect, ms, timeoutMs, keepAliveMs);
        }

        public Settings withGiOnConnect(boolean gi) {
            return new Settings(host, port, apTitle, aeQualifier, rcbs, gi, pollMs, timeoutMs, keepAliveMs);
        }

        public Settings withAcse(int[] title, Integer qualifier) {
            return new Settings(host, port, title, qualifier, rcbs, giOnConnect, pollMs, timeoutMs, keepAliveMs);
        }

        public Settings withTimeouts(int timeout, int keepAlive) {
            return new Settings(host, port, apTitle, aeQualifier, rcbs, giOnConnect, pollMs, timeout, keepAlive);
        }

        /** {@code "1,1,1,999,1"} -> dizi; null / bos -> null. */
        public static int[] parseApTitle(String s) {
            if (s == null || s.isBlank()) return null;
            String[] parts = s.trim().split("\\s*[,.]\\s*");
            int[] r = new int[parts.length];
            try {
                for (int i = 0; i < parts.length; i++) r[i] = Integer.parseInt(parts[i]);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("gecersiz ap_title: " + s);
            }
            return r;
        }
    }

    /** Izlenen tek yaprak: nesne referansi + FC. */
    public record Target(String ref, Fc fc) {
        public Target {
            if (ref == null || ref.isBlank() || fc == null) throw new IllegalArgumentException("ref / fc bos");
        }

        public String key() {
            return ref + "|" + fc;
        }
    }

    /** Referansin kullanilamama sebebi. */
    public enum Problem {
        /** Modelde yok. */
        NOT_FOUND,
        /** Var ama temel oznitelik (yaprak) degil, ornek: DO'nun kendisi. */
        NOT_LEAF,
        /** Modelde var ama IED okumayi reddetti. */
        READ_FAILED
    }

    /**
     * Tek deger. {@code leaf} yerel modeldeki dugumdur; YALNIZ geri cagirim icinde
     * okunmali, saklanmamalidir.
     *
     * @param good kardes q'nun validity'si GOOD ya da q yok
     * @param validity q yoksa null
     * @param time kardes t (UTC); yoksa ya da gecersizse null
     * @param cause interrogation, spontaneous ya da periodic
     */
    public record Sample(Target target, BasicDataAttribute leaf, boolean good, String validity, Instant time,
                         String cause) {
    }

    /** Istemci olaylari. onSample / onProblem kilit altinda cagrilir: hizli donmeli. */
    public interface Listener {
        /** Iliski kuruldu ve model okundu (izleme baslamadan once). */
        default void onConnected(String host, ServerModel model) {
        }

        /** Kurulmus bir baglanti koptu. */
        default void onDisconnected(String reason) {
        }

        /** Baglanti ya da model okuma basarisiz. */
        default void onConnectFailed(String host, String reason) {
        }

        default void onProblem(Target target, Problem problem, String detail) {
        }

        void onSample(Sample sample);
    }

    /** Anlik durum. */
    public record Status(boolean connected, String host, long reports, long reconnects, String lastError,
                         int rcbEnabled, long pollMs) {
    }

    private final String name;
    private final Settings s;
    private final List<Target> targets;
    private final Listener listener;
    private final ClientSap sap;
    private final Object lock = new Object();
    private final Set<String> loggedOnce = ConcurrentHashMap.newKeySet();
    private final AtomicLong reports = new AtomicLong();

    private volatile boolean stopped;
    private volatile Session current;
    private Thread thread;

    private volatile boolean connected;
    private volatile long reconnects;
    private volatile String lastError = "";
    private volatile int rcbEnabled;
    /** Son yoklama turunun suresi (ms); yoklama yoksa -1. */
    private volatile long lastPollMs = -1;
    private volatile long rcbRetryMs = RCB_RETRY_MS;
    /** LN okumasini reddeden LN + FC'ler ("LN|FC"); oturumlar arasi korunur. */
    private final Set<String> lnRed = ConcurrentHashMap.newKeySet();
    /** Ust uste kac oturum, hic LN okumasi basaramadan LN okumasinda zaman asimiyla bitti. */
    private volatile int lnZamanAsimi;
    /** LN okumasina yanit vermeyen IED: bu istemci boyunca DO basina okunur. */
    private volatile boolean lnKapali;
    /**
     * Tamponlu RCB (BRCB) basina son alinan EntryID ("IED|ornek referansi" -> bayt).
     * Yeniden baglanmada etkinlestirmeden once yazilir: IED tamponu bastan degil
     * kalinan yerden yollar (yalniz kopukken kacan girdiler gelir). Surec boyunca
     * (IED ayari degisip istemci yeniden kurulsa da) tutulur; gateway yeniden
     * baslarsa tampon bastan gelir, Grid ayni zaman etiketli ayni degeri tekrar sayar.
     */
    private static final Map<String, byte[]> SON_ENTRY_ID = new ConcurrentHashMap<>();
    /** Grid BRCB adresi basina en son tutulan ornek ("IED|grid adresi" -> ornek referansi). */
    private static final Map<String, String> TUTULAN_BRCB = new ConcurrentHashMap<>();
    /**
     * Daha once tutulan BRCB ornegi mesgulse (IED onceki baglantiyi henuz dusurmedi)
     * bu sure baska ornege gecilmez: tampon o ornekte. Sonra bos ornek alinir.
     */
    static final long BRCB_BEKLEME_MS = 60_000;
    /** "IED|grid adresi" -> tutulan ornegin mesgul goruldugu ilk an (nanoTime). */
    private final Map<String, Long> brcbMesgulBas = new ConcurrentHashMap<>();

    public Iec61850Client(String name, Settings settings, List<Target> targets, Listener listener) {
        this.name = name;
        this.s = settings;
        this.targets = List.copyOf(targets);
        this.listener = listener;
        this.sap = newSap(settings);
    }

    /** Testler icin: mesgul RCB yeniden deneme araligi (start'tan once). */
    void rcbRetryMs(long ms) {
        rcbRetryMs = Math.max(10, ms);
    }

    public synchronized void start() {
        if (thread != null) return;
        thread = new Thread(this::runLoop, "iec61850-" + name);
        thread.setDaemon(true);
        thread.start();
    }

    /**
     * Bilerek durdurma. Iliskiyi oturum is parcacigi kapatir (once RCB'leri
     * birakir); bean iliskisi ayni anda iki is parcacigindan kullanilmaz. Is
     * parcacigi {@link #CLOSE_JOIN_MS} icinde bitmezse iliski zorla kapatilir.
     */
    @Override
    public void close() {
        stopped = true;
        Thread t = thread;
        if (t == null) return;
        t.interrupt();
        join(t, CLOSE_JOIN_MS);
        if (t.isAlive()) {
            // Baglanmamisken (ulasilamayan adrese TCP baglantisi bekleniyor) olagan; uyari degil.
            LOG.log(connected ? Level.WARNING : Level.FINE, "iec61850_close_forced ied=" + name + " (oturum "
                    + CLOSE_JOIN_MS + " ms icinde bitmedi, iliski zorla kapatiliyor)");
            Session ss = current;
            if (ss != null) ss.close();
            join(t, 1000);
        }
    }

    private static void join(Thread t, long ms) {
        try {
            t.join(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public Status status() {
        return new Status(connected, s.host(), reports.get(), reconnects, lastError, rcbEnabled, lastPollMs);
    }

    public Settings settings() {
        return s;
    }

    /**
     * Tek seferlik model kesfi (CLI ve Grid kesif isi): baglanir, modeli ve RCB
     * degerlerini okur, kapatir. RCB etkinlestirilmez. RCB okunamazsa model yine doner.
     */
    public static ServerModel discover(Settings settings) throws IOException, ServiceError {
        ClientAssociation a = newSap(settings).associate(InetAddress.getByName(settings.host()), settings.port(),
                null, new ClientEventListener() {
                    @Override
                    public void newReport(Report report) {
                        // kesifte rapor beklenmez
                    }

                    @Override
                    public void associationClosed(IOException e) {
                        // kesif bitince kapanir
                    }
                });
        try {
            ServerModel model = a.retrieveModel();
            List<Rcb> rcbs = new ArrayList<>(model.getBrcbs());
            rcbs.addAll(model.getUrcbs());
            for (Rcb r : rcbs) {
                try {
                    a.getRcbValues(r);
                } catch (ServiceError e) {
                    // okunamayan blok listede DatSet'siz gorunur
                }
            }
            return model;
        } finally {
            a.close();
        }
    }

    private static ClientSap newSap(Settings s) {
        ClientSap sap = new ClientSap();
        // bean: yanit zaman asimi ayni zamanda TCP baglanti zaman asimidir (ClientTSap messageTimeout).
        sap.setResponseTimeout(s.timeoutMs());
        sap.setMessageFragmentTimeout(s.timeoutMs());
        // Grid'deki ap_title / ae_qualifier karsi tarafin (IED, "called") degeridir.
        // Bizim (calling) degerlerimiz bean varsayilaninda kalir: 1,1,999,1 / 12.
        if (s.apTitle() != null) sap.setApTitleCalled(s.apTitle());
        if (s.aeQualifier() != null) sap.setAeQualifierCalled(s.aeQualifier());
        return sap;
    }

    // ---- baglanti dongusu ----------------------------------------------------

    private void runLoop() {
        int attempt = 0;
        while (!stopped) {
            Session ss = new Session();
            boolean established = false;
            String reason;
            try {
                ss.open();
                established = true;
                connected = true;
                lastError = "";
                LOG.info(() -> "iec61850_connected ied=" + name + " host=" + s.host() + ":" + s.port()
                        + " ld=" + ss.model.getChildren().size());
                listener.onConnected(s.host(), ss.model);
                reason = ss.run();
            } catch (IOException e) {
                reason = describe(e);
            } catch (ServiceError e) {
                reason = "ServiceError " + e.getErrorCode() + ": " + e.getMessage();
            } catch (RuntimeException e) {
                // Dinleyici ya da bean hatasi baglanti dongusunu oldurmemeli.
                LOG.log(Level.SEVERE, "iec61850_unexpected ied=" + name, e);
                reason = e.toString();
            } finally {
                // Bilerek durdurma: tutulan RCB'ler birakilir. Beklenmedik kopmada birakilacak bir sey yok.
                // Birakma hatasi iliskinin kapanmasini engellememeli.
                try {
                    if (stopped) ss.release();
                } catch (RuntimeException e) {
                    LOG.log(Level.FINE, "sa_rcb_release_failed ied=" + name, e);
                } finally {
                    ss.close();
                }
            }
            lastError = reason == null ? "" : reason;
            if (stopped) break;
            String r = reason;
            if (established) {
                connected = false;
                rcbEnabled = 0;
                LOG.warning(() -> "iec61850_link_down ied=" + name + " host=" + s.host() + " sebep=" + r);
                listener.onDisconnected(reason);
            } else {
                LOG.fine(() -> "iec61850_connect_failed ied=" + name + " host=" + s.host() + " sebep=" + r);
                listener.onConnectFailed(s.host(), reason);
            }
            reconnects++;
            if (established) attempt = 0;
            long delay = Math.min(MAX_BACKOFF_MS, 1000L << Math.min(attempt, 5));
            attempt++;
            sleepQuietly(delay);
        }
        connected = false;
        rcbEnabled = 0;
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

    private static String describe(Throwable e) {
        String m = e.getMessage();
        if (m == null && e.getCause() != null) m = e.getCause().toString();
        return e.getClass().getSimpleName() + (m == null ? "" : ": " + m);
    }

    private static String describe(ServiceError e) {
        return "ServiceError " + e.getErrorCode() + (e.getMessage() == null ? "" : " " + e.getMessage());
    }

    private static String key(BasicDataAttribute b) {
        return b.getReference() + "|" + b.getFc();
    }

    private void logOnce(String k, Level level, String msg) {
        if (loggedOnce.add(k)) LOG.log(level, msg);
    }

    /** Izlenen yaprak ve yerel modelde ona ait q / t. */
    private static final class Watched {
        final Target target;
        final BasicDataAttribute leaf;
        final BdaQuality q;
        final BdaTimestamp t;
        /** Grup okunamazsa tek basina okunacak dugum: q'yu tasiyan DO/SDO, yoksa yaprak. */
        final FcModelNode owner;

        Watched(Target target, BasicDataAttribute leaf, BdaQuality q, BdaTimestamp t, FcModelNode owner) {
            this.target = target;
            this.leaf = leaf;
            this.q = q;
            this.t = t;
            this.owner = owner;
        }
    }

    /** Ayni ust DO'ya (LN altindaki FC'li veri nesnesi) dusen yapraklar tek istekle okunur. */
    private static final class Group {
        final FcModelNode node;
        final List<Watched> members = new ArrayList<>();

        Group(FcModelNode node) {
            this.node = node;
        }
    }

    /**
     * Ayni mantiksal dugum + FC'deki DO gruplari TEK istekle okunur (MMS "LN$FC").
     * Olcum (kullanici simulatoru, 1116 sinyal / 1078 DO, 2026-11-26): DO basina tur
     * ~9 sn, LN duzeyinde 202 istekle ~0,7 sn; degerler ayni. Yanit yapisi sunucunun
     * LN'sindeki o FC'li TUM DO'lardir: istek de hepsini model sirasiyla tasir.
     * Sunucu reddederse bu LN oturum boyunca DO basina okunur (eski yol).
     */
    private static final class LnGroup {
        final String lnRef;
        final Fc fc;
        /** LN'deki bu FC'li tum DO'lar (yerel model, model sirasi). */
        final List<FcModelNode> dos;
        /** Izlenen DO gruplari (bu LN + FC). */
        final List<Group> groups = new ArrayList<>();
        /** LN okumasi reddedildi: oturum boyunca DO basina. */
        boolean doBasina;

        LnGroup(String lnRef, Fc fc, List<FcModelNode> dos) {
            this.lnRef = lnRef;
            this.fc = fc;
            this.dos = dos;
        }
    }

    @FunctionalInterface
    private interface Request {
        void run() throws IOException, ServiceError;
    }

    /** Tek Grid RCB adresinin etkinlestirme sonucu. */
    private enum RcbState {
        ENABLED,
        /** Baska istemci tutuyor (RptEna acik / rezervasyon ya da etkinlestirme reddi): yeniden denenir. */
        BUSY,
        /** Yapisal (blok yok, veri kumesi bos / modelde yok, okunamadi): yeniden denenmez. */
        FAILED
    }

    /** tryEnable sonucu: reason null = etkin. */
    private record Attempt(String reason, boolean busy) {
        static final Attempt OK = new Attempt(null, false);

        static Attempt busy(String reason) {
            return new Attempt(reason, true);
        }

        static Attempt failed(String reason) {
            return new Attempt(reason, false);
        }
    }

    // ---- tek iliski (association) oturumu ------------------------------------

    private final class Session implements ClientEventListener {
        private volatile ClientAssociation assoc;
        private ServerModel model;
        private final CountDownLatch closedLatch = new CountDownLatch(1);
        private volatile String closeReason = "baglanti kapandi";
        private volatile long lastActivity = System.nanoTime();
        // asagidakiler 'lock' altinda
        private final Map<String, Watched> byLeaf = new HashMap<>();
        private final Map<String, List<Watched>> byQuality = new HashMap<>();
        private final Map<String, BasicDataAttribute> index = new HashMap<>();
        private final List<Group> groups = new ArrayList<>();
        private final List<LnGroup> lnGroups = new ArrayList<>();
        /** Bu oturumda en az bir LN okumasi basarili oldu (hat LN okumasini tasiyor). */
        private boolean lnBasarili;
        /**
         * Etkin BRCB'nin veri kumesi -> BRCB referansi. Rapor blogu adini tasimaz;
         * EntryID'si olan rapor (yalniz BRCB) veri kumesinden eslenir. Ayni kumeyi
         * iki BRCB tasiyorsa "" (belirsiz: EntryID tutulmaz). Rapor is parcacigi okur.
         */
        private final Map<String, String> brcbByDataSet = new ConcurrentHashMap<>();
        /** Bu oturumda EntryID ile kalinan yerden surdurulen BRCB'ler (GI istenmez). */
        private final Set<Rcb> surdurulen = ConcurrentHashMap.newKeySet();
        // yalniz oturum is parcaciginda
        private final List<Rcb> enabled = new ArrayList<>();
        /** IED'de tutabilecegimiz bloklar (rezerve URCB, etkin ya da etkinlestirilmekte olan): kapanista birakilir. */
        private final Set<Rcb> held = new LinkedHashSet<>();
        /** Baska istemcide oldugu icin etkinlesmeyen Grid adresleri. */
        private final List<RcbRef> pending = new ArrayList<>();

        void open() throws IOException, ServiceError {
            InetAddress addr = InetAddress.getByName(s.host());
            assoc = sap.associate(addr, s.port(), null, this);
            current = this;
            if (stopped) throw new IOException("durduruldu");
            model = assoc.retrieveModel(); // iliski basina bir kez
            touch();
        }

        /** Oturum bitene kadar calisir; bitis sebebini doner. */
        String run() throws IOException {
            resolve();
            enableReports();
            // RCB'ler once etkinlesir: ilk okuma ile etkinlestirme arasindaki degisim kacmaz.
            readAll("interrogation");
            if (s.giOnConnect()) startGi();
            boolean polling = s.pollMs() > 0;
            long pollNs = s.pollMs() * 1_000_000L;
            long keepNs = s.keepAliveMs() * 1_000_000L;
            long retryNs = rcbRetryMs * 1_000_000L;
            long nextPoll = System.nanoTime() + pollNs;
            long nextRetry = System.nanoTime() + retryNs;
            while (!stopped) {
                long now = System.nanoTime();
                long waitNs = (lastActivity + keepNs) - now;
                if (polling) waitNs = Math.min(waitNs, nextPoll - now);
                if (!pending.isEmpty()) waitNs = Math.min(waitNs, nextRetry - now);
                long waitMs = Math.max(1, Math.min(1000, waitNs / 1_000_000));
                try {
                    if (closedLatch.await(waitMs, TimeUnit.MILLISECONDS)) return closeReason;
                } catch (InterruptedException e) {
                    return "durduruldu";
                }
                if (stopped) break;
                if (!pending.isEmpty() && System.nanoTime() - nextRetry >= 0) {
                    retryReports();
                    nextRetry = System.nanoTime() + retryNs;
                    if (stopped) break;
                }
                now = System.nanoTime();
                if (polling && now - nextPoll >= 0) {
                    long t0 = System.nanoTime();
                    readAll("periodic");
                    long sure = System.nanoTime() - t0;
                    lastPollMs = sure / 1_000_000;
                    // Aralik turun BASINDAN sayilir: bir deger en gec bir aralik sonra
                    // yeniden okunur (tur bitince bekleseydik gecikme aralik + tur olurdu).
                    // Tur araliktan uzun surduyse IED'ye ara vermeden istek gitmesin:
                    // sonraki tur en az bir tur suresi kadar bekler (IED ve ag korunur).
                    if (sure > pollNs) {
                        logOnce("poll_slow", Level.WARNING, "sa_poll_slow ied=" + name + " tur_ms=" + lastPollMs
                                + " aralik_ms=" + s.pollMs() + " (sonraki tur ertelenir; noktalari azaltin ya da "
                                + "rapor blogu kullanin)");
                        nextPoll = System.nanoTime() + sure;
                    } else {
                        nextPoll = t0 + pollNs;
                    }
                } else if (now - (lastActivity + keepNs) >= 0) {
                    probe();
                }
            }
            return "durduruldu";
        }

        void close() {
            ClientAssociation a = assoc;
            if (a != null) a.close();
        }

        /**
         * Bilerek durdurmada tutulan bloklari birakir: RptEna=false, URCB icin
         * rezervasyon iptali. IED eski iliskinin bittigini gec fark edebilir (bean
         * sunucusu BRCB'yi hic birakmaz); birakilmazsa yeni iliski "baska bir
         * istemcide etkin" gorur. Sinirli: kisa yanit zaman asimi, toplam sure
         * siniri, ilk ag hatasinda / zaman asiminda birakilir. Hatalar yok sayilir.
         */
        void release() {
            ClientAssociation a = assoc;
            if (held.isEmpty() || a == null || !a.isOpen() || closedLatch.getCount() == 0) return;
            // close() kesmesi bayrakta kaldiysa bean ilk istegi hemen "zaman asimi" sayar.
            Thread.interrupted();
            a.setResponseTimeout(Math.min(s.timeoutMs(), RELEASE_TIMEOUT_MS));
            long deadline = System.nanoTime() + RELEASE_BUDGET_MS * 1_000_000;
            int done = 0;
            try {
                for (Rcb rcb : held) {
                    if (System.nanoTime() - deadline >= 0) break;
                    releaseCall(() -> a.disableReporting(rcb), rcb);
                    if (rcb instanceof Urcb u) releaseCall(() -> a.cancelUrcbReservation(u), rcb);
                    done++;
                }
            } catch (IOException e) {
                LOG.fine(() -> "sa_rcb_release_aborted ied=" + name + " " + describe(e));
            }
            int n = done;
            LOG.fine(() -> "sa_rcb_released ied=" + name + " blok=" + n + "/" + held.size());
            held.clear();
            enabled.clear();
        }

        private void releaseCall(Request r, Rcb rcb) throws IOException {
            try {
                r.run();
            } catch (ServiceError e) {
                if (e.getErrorCode() == ServiceError.TIMEOUT) throw new IOException("yanit gelmedi", e);
                LOG.fine(() -> "sa_rcb_release_failed ied=" + name + " rcb=" + rcb.getReference() + " " + describe(e));
            } catch (RuntimeException e) {
                // ornek: Resv'siz URCB (bean NPE); digerleri yine birakilsin
                LOG.fine(() -> "sa_rcb_release_failed ied=" + name + " rcb=" + rcb.getReference() + " " + e);
            }
        }

        private void touch() {
            lastActivity = System.nanoTime();
        }

        /**
         * Istek sarmalayici: TIMEOUT hattin kopuk oldugu sayilir (IOException).
         * Diger ServiceError'lar IED'nin yanit verdigini gosterir; hat canlidir.
         */
        private void call(Request r) throws IOException, ServiceError {
            try {
                r.run();
                touch();
            } catch (ServiceError e) {
                if (e.getErrorCode() == ServiceError.TIMEOUT) {
                    throw new IOException("yanit gelmedi (" + s.timeoutMs() + " ms)", e);
                }
                touch();
                throw e;
            }
        }

        // ---- noktalarin modelde bulunmasi -----------------------------------

        private void resolve() {
            synchronized (lock) {
                Map<String, Group> byGroup = new LinkedHashMap<>();
                for (Target t : targets) {
                    ModelNode n = model.findModelNode(t.ref(), t.fc());
                    if (n == null) {
                        problem(t, Problem.NOT_FOUND, "modelde yok");
                        continue;
                    }
                    if (!(n instanceof BasicDataAttribute leaf)) {
                        problem(t, Problem.NOT_LEAF, n.getClass().getSimpleName());
                        continue;
                    }
                    // Kalite ve zaman: yapragi tasiyan veri nesnesinin (DO ya da SDO) q / t'si.
                    // Ornek: X.Pos.stVal -> X.Pos.q; X.A.phsA.cVal.mag.f -> X.A.phsA.q.
                    BdaQuality q = null;
                    BdaTimestamp ts = null;
                    FcModelNode owner = leaf;
                    for (ModelNode p = leaf.getParent(); p instanceof FcModelNode f; p = p.getParent()) {
                        if (f.getChild("q") instanceof BdaQuality qq) {
                            q = qq;
                            ts = f.getChild("t") instanceof BdaTimestamp tt ? tt : null;
                            owner = f;
                            break;
                        }
                    }
                    Watched w = new Watched(t, leaf, q, ts, owner);
                    byLeaf.put(key(leaf), w);
                    index.put(key(leaf), leaf);
                    if (q != null) {
                        index.put(key(q), q);
                        byQuality.computeIfAbsent(key(q), k -> new ArrayList<>()).add(w);
                    }
                    if (ts != null) index.put(key(ts), ts);
                    FcModelNode top = leaf;
                    while (top.getParent() instanceof FcModelNode p) top = p;
                    FcModelNode g = top;
                    byGroup.computeIfAbsent(g.getReference() + "|" + g.getFc(), k -> new Group(g)).members.add(w);
                }
                groups.addAll(byGroup.values());
                // LN + FC kumeleri (yoklama turu bunlarla okunur; bkz. LnGroup).
                Map<String, LnGroup> byLn = new LinkedHashMap<>();
                for (Group g : groups) {
                    ModelNode ln = g.node.getParent();
                    Fc fc = g.node.getFc();
                    LnGroup l = byLn.computeIfAbsent(ln.getReference() + "|" + fc, k -> {
                        List<FcModelNode> dos = new ArrayList<>();
                        for (ModelNode d : ln.getChildren()) {
                            if (d instanceof FcModelNode f && !(f instanceof Rcb) && f.getFc() == fc) dos.add(f);
                        }
                        LnGroup yeni = new LnGroup(ln.getReference().toString(), fc, dos);
                        yeni.doBasina = lnRed.contains(yeni.lnRef + "|" + fc);
                        return yeni;
                    });
                    l.groups.add(g);
                }
                lnGroups.addAll(byLn.values());
            }
        }

        private void problem(Target t, Problem p, String detail) {
            if (!stopped) listener.onProblem(t, p, detail);
        }

        // ---- raporlar --------------------------------------------------------

        private void enableReports() throws IOException {
            for (RcbRef r : s.rcbs()) {
                if (enableRef(r, false) == RcbState.BUSY) pending.add(r);
            }
            rcbEnabled = enabled.size();
            if (enabled.isEmpty() && !s.rcbs().isEmpty()) {
                logOnce("none", Level.WARNING, "sa_rcb_none_enabled ied=" + name + " (hicbir rapor blogu "
                        + "etkinlesmedi; yoklama " + (s.pollMs() > 0 ? s.pollMs() + " ms" : "KAPALI")
                        + (pending.isEmpty() ? "" : "; mesgul bloklar yeniden denenecek") + ")");
            } else if (!enabled.isEmpty()) {
                loggedOnce.remove("none");
            }
        }

        /**
         * Tek Grid adresi: adaylar sirayla denenir, ilk etkinlesen kullanilir.
         * BRCB'de daha once tutulan ornek once denenir; mesgulse BRCB_BEKLEME_MS
         * boyunca baska ornege gecilmez (tampon ve EntryID o ornekte).
         */
        private RcbState enableRef(RcbRef r, boolean retry) throws IOException {
            List<Rcb> candidates = candidates(r);
            if (candidates.isEmpty()) {
                logOnce("nf|" + r, Level.WARNING, "sa_rcb_not_found ied=" + name + " rcb=" + r
                        + " (modelde yok; yoklama " + (s.pollMs() > 0 ? "var" : "YOK") + ")");
                return RcbState.FAILED;
            }
            String anahtar = name + "|" + r;
            String onceki = r.buffered() ? TUTULAN_BRCB.get(anahtar) : null;
            if (onceki != null) {
                candidates = new ArrayList<>(candidates);
                for (int i = 0; i < candidates.size(); i++) {
                    if (candidates.get(i).getReference().toString().equals(onceki)) {
                        candidates.add(0, candidates.remove(i));
                        break;
                    }
                }
            }
            String fail = null;
            boolean busy = false;
            for (Rcb rcb : candidates) {
                if (enabled.contains(rcb)) {
                    // Grid'de ayni blok iki adresle verilmis; ikincisi icin mesgul sayilmaz.
                    fail = rcb.getReference() + ": bu baglantida baska bir adresle zaten etkin";
                    continue;
                }
                Attempt at = tryEnable(rcb);
                if (at.reason() != null && at.busy() && rcb.getReference().toString().equals(onceki)) {
                    long bas = brcbMesgulBas.computeIfAbsent(anahtar, k -> System.nanoTime());
                    if (System.nanoTime() - bas < BRCB_BEKLEME_MS * 1_000_000L) {
                        logOnce("bw|" + r, Level.INFO, "sa_brcb_wait ied=" + name + " rcb=" + onceki
                                + " (onceki baglantida tutuluyor; tampon bu ornekte, en cok "
                                + BRCB_BEKLEME_MS / 1000 + " sn beklenir)");
                        return RcbState.BUSY;
                    }
                }
                if (at.reason() == null) {
                    if (r.buffered()) {
                        TUTULAN_BRCB.put(anahtar, rcb.getReference().toString());
                        brcbMesgulBas.remove(anahtar);
                        loggedOnce.remove("bw|" + r);
                    }
                    loggedOnce.remove("ef|" + r);
                    LOG.info(() -> "sa_rcb_enabled ied=" + name + " rcb=" + rcb.getReference()
                            + (rcb instanceof Brcb ? " (BR)" : " (RP)") + " veri_kumesi="
                            + rcb.getDatSet().getStringValue() + (retry ? " (yeniden deneme)" : ""));
                    return RcbState.ENABLED;
                }
                busy |= at.busy();
                fail = rcb.getReference() + ": " + at.reason();
            }
            String f = fail;
            boolean b = busy;
            logOnce("ef|" + r, Level.WARNING, "sa_rcb_enable_failed ied=" + name + " rcb=" + r + " sebep=" + f
                    + (b ? " (" + rcbRetryMs / 1000 + " sn'de bir yeniden denenir)" : ""));
            return busy ? RcbState.BUSY : RcbState.FAILED;
        }

        /**
         * Mesgul bloklari yeniden dener. Gec etkinlesen blokta arada kacan degisim
         * olmasin diye gi_on_connect ise GI yapilir, degilse (ya da GI olmazsa)
         * tum noktalar okunur.
         */
        private void retryReports() throws IOException {
            List<Rcb> fresh = new ArrayList<>();
            for (Iterator<RcbRef> it = pending.iterator(); it.hasNext() && !stopped; ) {
                RcbState st = enableRef(it.next(), true);
                if (st != RcbState.BUSY) it.remove();
                if (st == RcbState.ENABLED) fresh.add(enabled.get(enabled.size() - 1));
            }
            if (fresh.isEmpty()) return;
            rcbEnabled = enabled.size();
            loggedOnce.remove("none");
            boolean gi = s.giOnConnect();
            for (Rcb rcb : fresh) {
                if (gi && !startGi(rcb)) gi = false;
            }
            if (!gi) readAll("interrogation");
        }

        /**
         * Grid'deki ad modelde birebir varsa yalniz o; yoksa ayni LN'de ad + sira
         * no (ad01, ad02...) ornekleri sirayla. SCL'de RptEnabled max>1 olan blok
         * IED'de numarali orneklerle gorunur. Birebir ad varken baska ornege
         * GECILMEZ: o ornek baska bir istemciye (SCADA) ayrilmis olabilir.
         */
        private List<Rcb> candidates(RcbRef r) {
            Rcb exact = r.buffered() ? model.getBrcb(r.beanReference()) : model.getUrcb(r.beanReference());
            if (exact != null) return List.of(exact);
            ModelNode ln = model.findModelNode(r.lnReference(), null);
            if (!(ln instanceof LogicalNode node)) return List.of();
            Pattern p = Pattern.compile(Pattern.quote(r.name()) + "(\\d{1,2})");
            Collection<? extends Rcb> all = r.buffered() ? node.getBrcbs() : node.getUrcbs();
            List<Rcb> found = new ArrayList<>();
            for (Rcb rcb : all) {
                if (p.matcher(rcb.getName()).matches()) found.add(rcb);
            }
            found.sort(Comparator.comparingInt((Rcb x) -> {
                Matcher m = p.matcher(x.getName());
                return m.matches() ? Integer.parseInt(m.group(1)) : 0;
            }).thenComparing(Rcb::getName));
            return found;
        }

        /** IOException hat kopuk demektir. */
        private Attempt tryEnable(Rcb rcb) throws IOException {
            boolean buffered = rcb instanceof Brcb;
            try {
                call(() -> assoc.getRcbValues(rcb));
                if (rcb.getRptEna() != null && rcb.getRptEna().getValue()) {
                    return Attempt.busy("baska bir istemcide etkin");
                }
                String ds = rcb.getDatSet() == null ? null : rcb.getDatSet().getStringValue();
                if (ds == null || ds.isEmpty()) return Attempt.failed("veri kumesi (DatSet) bos");
                // Veri kumesi modelde yoksa bean gelen raporu cozemez ve iliskiyi KAPATIR.
                if (model.getDataSet(ds.replace('$', '.')) == null) {
                    return Attempt.failed("veri kumesi modelde yok: " + ds);
                }
                // Yanit gelmeden kesilen istek IED'de uygulanmis olabilir: kapanista birakilsin.
                // Reddedilirse listeden cikar; baska istemcinin blogu birakilmaz.
                held.add(rcb);
                if (!buffered) {
                    try {
                        call(() -> assoc.reserveUrcb((Urcb) rcb));
                    } catch (ServiceError e) {
                        held.remove(rcb);
                        return Attempt.busy("rezerve edilemedi (baska istemcide olabilir): " + describe(e));
                    }
                }
                // Eslesme etkinlestirmeden ONCE: tampon girdileri hemen ardindan gelir.
                String kume = ds.replace('.', '$');
                String ref = rcb.getReference().toString();
                if (buffered) brcbByDataSet.merge(kume, ref, (eski, yeni) -> eski.equals(yeni) ? eski : "");
                try {
                    configure(rcb);
                    if (buffered) resume((Brcb) rcb);
                    call(() -> assoc.enableReporting(rcb));
                } catch (ServiceError e) {
                    if (!buffered) cancelQuietly((Urcb) rcb);
                    if (buffered) brcbByDataSet.remove(kume, ref);
                    held.remove(rcb);
                    return Attempt.busy("etkinlestirilemedi: " + describe(e));
                }
                enabled.add(rcb);
                return Attempt.OK;
            } catch (ServiceError e) {
                return Attempt.failed(describe(e));
            }
        }

        /**
         * BRCB'yi kalinan yerden surdurur: onceki oturumda alinan son EntryID
         * yazilir, IED tampondan yalniz SONRAKI girdileri yollar. EntryID yoksa ya
         * da IED reddederse (girdi tampondan dusmus) tampon bastan gelir.
         */
        private void resume(Brcb rcb) throws IOException {
            String ref = rcb.getReference().toString();
            byte[] id = SON_ENTRY_ID.get(name + "|" + ref);
            if (id == null || rcb.getEntryId() == null) return;
            rcb.getEntryId().setValue(id.clone());
            List<ServiceError> errs = assoc.setRcbValues(rcb, false, false, false, false, false, false, false, true);
            touch();
            for (ServiceError e : errs) {
                if (e == null) continue;
                if (e.getErrorCode() == ServiceError.TIMEOUT) throw new IOException("yanit gelmedi (EntryID)", e);
                LOG.fine(() -> "sa_brcb_resume_rejected ied=" + name + " rcb=" + ref + " " + describe(e)
                        + " (tampon bastan gelir)");
                return;
            }
            surdurulen.add(rcb);
            LOG.fine(() -> "sa_brcb_resumed ied=" + name + " rcb=" + ref);
        }

        private void cancelQuietly(Urcb urcb) throws IOException {
            try {
                call(() -> assoc.cancelUrcbReservation(urcb));
            } catch (ServiceError e) {
                // onemsiz
            }
        }

        /**
         * Tetikleyiciler: veri degisimi, kalite degisimi, GI. Istege bagli alanlar:
         * sira no, rapor zamani, veri kumesi adi, dahil olma sebebi. Veri referansi
         * (dataRef) ISTENMEZ: bean raporu veri kumesi uye sirasiyla cozer, referanslari
         * atlar; yalniz raporu buyutur. IED yazmayi reddederse bloktaki degerlerle devam edilir.
         */
        private void configure(Rcb rcb) throws IOException {
            BdaTriggerConditions tr = rcb.getTrgOps();
            BdaOptFlds of = rcb.getOptFlds();
            boolean trCh = false;
            boolean ofCh = false;
            if (tr != null) {
                if (!tr.isDataChange()) {
                    tr.setDataChange(true);
                    trCh = true;
                }
                if (!tr.isQualityChange()) {
                    tr.setQualityChange(true);
                    trCh = true;
                }
                if (!tr.isGeneralInterrogation()) {
                    tr.setGeneralInterrogation(true);
                    trCh = true;
                }
            }
            if (of != null) {
                if (!of.isDataSetName()) {
                    of.setDataSetName(true);
                    ofCh = true;
                }
                if (!of.isReasonForInclusion()) {
                    of.setReasonForInclusion(true);
                    ofCh = true;
                }
                if (!of.isSequenceNumber()) {
                    of.setSequenceNumber(true);
                    ofCh = true;
                }
                if (!of.isReportTimestamp()) {
                    of.setReportTimestamp(true);
                    ofCh = true;
                }
                // BRCB: kalinan yerden surdurmek icin raporda EntryID gerekir.
                if (rcb instanceof Brcb && !of.isEntryId()) {
                    of.setEntryId(true);
                    ofCh = true;
                }
            }
            if (!trCh && !ofCh) return;
            List<ServiceError> errs = assoc.setRcbValues(rcb, false, false, ofCh, false, trCh, false, false, false);
            touch();
            List<String> bad = new ArrayList<>();
            for (ServiceError e : errs) {
                if (e == null) continue;
                if (e.getErrorCode() == ServiceError.TIMEOUT) throw new IOException("yanit gelmedi (RCB ayari)", e);
                bad.add(describe(e));
            }
            if (!bad.isEmpty()) {
                logOnce("pr|" + rcb.getReference(), Level.INFO, "sa_rcb_param_rejected ied=" + name + " rcb="
                        + rcb.getReference() + " " + bad + " (IED'deki ayarla devam)");
                try {
                    call(() -> assoc.getRcbValues(rcb)); // yerel kopya IED'dekiyle ayni olsun
                } catch (ServiceError e) {
                    // onemsiz: rapor cozumu rapordaki OptFlds'e bakar
                }
            }
        }

        private void startGi() throws IOException {
            for (Rcb rcb : enabled) startGi(rcb);
        }

        private boolean startGi(Rcb rcb) throws IOException {
            // EntryID ile surdurulen BRCB'de GI istenmez: tampon kacan girdileri
            // yollarken gelen GI gonderimi keser (libiec61850'de girdi atlandi);
            // guncel deger baglanirken okumayla zaten gelir.
            if (surdurulen.contains(rcb)) return true;
            try {
                call(() -> assoc.startGi(rcb));
                return true;
            } catch (ServiceError e) {
                LOG.fine(() -> "sa_rcb_gi_failed ied=" + name + " rcb=" + rcb.getReference() + " " + describe(e));
                return false;
            }
        }

        @Override
        public void newReport(Report report) {
            if (stopped || current != this) return;
            reports.incrementAndGet();
            touch();
            List<FcModelNode> values = report.getValues();
            List<BdaReasonForInclusion> reasons = report.getReasonCodes();
            synchronized (lock) {
                // 1) Rapordaki yapraklarla yerel modeli guncelle (izlenen yaprak, q, t).
                Map<String, String> causeByKey = new LinkedHashMap<>();
                for (int i = 0; i < values.size(); i++) {
                    String cause = reasons != null && i < reasons.size() ? cause(reasons.get(i)) : "spontaneous";
                    for (BasicDataAttribute b : values.get(i).getBasicDataAttributes()) {
                        String k = key(b);
                        BasicDataAttribute m = index.get(k);
                        if (m == null) continue;
                        m.setValueFrom(b);
                        causeByKey.put(k, cause);
                    }
                }
                // 2) Degeri ya da kalitesi gelen noktalari bildir. q/t raporda yoksa
                //    modeldeki son bilinen q/t kullanilir (son okuma ya da rapor).
                Map<Watched, String> hit = new LinkedHashMap<>();
                for (Map.Entry<String, String> e : causeByKey.entrySet()) {
                    Watched w = byLeaf.get(e.getKey());
                    if (w != null) hit.putIfAbsent(w, e.getValue());
                    List<Watched> qs = byQuality.get(e.getKey());
                    if (qs != null) {
                        for (Watched x : qs) hit.putIfAbsent(x, e.getValue());
                    }
                }
                hit.forEach(this::emit);
            }
            // Isleme bittikten sonra: bu girdi alindi, yeniden baglanmada sonrakinden surer.
            if (report.getEntryId() != null && report.getEntryId().getValue() != null && report.getDataSetRef() != null) {
                String ref = brcbByDataSet.get(report.getDataSetRef().replace('.', '$'));
                if (ref != null && !ref.isEmpty()) SON_ENTRY_ID.put(name + "|" + ref, report.getEntryId().getValue().clone());
            }
        }

        private String cause(BdaReasonForInclusion r) {
            if (r == null) return "spontaneous";
            if (r.isGeneralInterrogation()) return "interrogation";
            if (r.isIntegrity()) return "periodic";
            return "spontaneous";
        }

        @Override
        public void associationClosed(IOException e) {
            closeReason = e == null ? "baglanti kapandi" : describe(e);
            closedLatch.countDown();
        }

        // ---- okuma -----------------------------------------------------------

        private void readAll(String cause) throws IOException {
            for (LnGroup l : lnGroups) {
                if (stopped) return;
                // Tek izlenen DO'lu LN'de LN okumasi istek kazandirmaz (fazla veri tasir).
                if (lnKapali || l.doBasina || l.groups.size() < 2) {
                    for (Group g : l.groups) {
                        if (stopped) return;
                        readGroup(g, cause);
                    }
                } else {
                    readLn(l, cause);
                }
            }
        }

        /**
         * LN + FC tek istek. Reddedilirse bu LN (yeniden baglanmalarda da) DO basina
         * okunur. Zaman asiminda iliski her durumda yeniden kurulur (call); oturumda hic
         * LN okumasi basarmadiysa sayilir, ust uste LN_ZAMAN_ASIMI_SINIRI oturum boyle
         * biterse bu IED'de LN okumasi kapanir. Basarili LN okumasindan sonraki hat
         * kopmasi sayilmaz.
         */
        private void readLn(LnGroup l, String cause) throws IOException {
            List<FcModelNode> kopyalar = new ArrayList<>(l.dos.size());
            for (FcModelNode d : l.dos) kopyalar.add((FcModelNode) d.copy());
            FcDataObject istek = new FcDataObject(new ObjectReference(l.lnRef), l.fc, kopyalar);
            try {
                call(() -> assoc.getDataValues(istek));
            } catch (IOException e) {
                if (!lnBasarili && e.getCause() instanceof ServiceError se && se.getErrorCode() == ServiceError.TIMEOUT
                        && ++lnZamanAsimi >= LN_ZAMAN_ASIMI_SINIRI && !lnKapali) {
                    lnKapali = true;
                    LOG.warning(() -> "sa_iec61850_ln_read_off ied=" + name + " (LN okumasi " + lnZamanAsimi
                            + " oturumdur yanitsiz; DO basina okunur)");
                }
                throw e;
            } catch (ServiceError | RuntimeException e) {
                l.doBasina = true;
                lnRed.add(l.lnRef + "|" + l.fc);
                logOnce("ln|" + l.lnRef + l.fc, Level.FINE, "sa_iec61850_ln_read_failed ied=" + name + " ln="
                        + l.lnRef + " fc=" + l.fc + " " + (e instanceof ServiceError se ? describe(se) : e.toString())
                        + " (DO basina okunur)");
                for (Group g : l.groups) {
                    if (stopped) return;
                    readGroup(g, cause);
                }
                return;
            }
            lnBasarili = true;
            lnZamanAsimi = 0;
            synchronized (lock) {
                for (int i = 0; i < l.dos.size(); i++) apply(l.dos.get(i), kopyalar.get(i));
                for (Group g : l.groups) {
                    for (Watched w : g.members) {
                        loggedOnce.remove("rf|" + w.target.key());
                        emit(w, cause);
                    }
                }
            }
        }

        private void readGroup(Group g, String cause) throws IOException {
            FcModelNode copy = (FcModelNode) g.node.copy();
            try {
                call(() -> assoc.getDataValues(copy));
            } catch (ServiceError e) {
                // getDataValues hep-ya-hic: grubun tek bir uyesi okunamazsa hicbiri gelmez.
                // Uyeleri tek tek (q'yu tasiyan DO/SDO ya da yaprak) dene.
                logOnce("rg|" + g.node.getReference() + g.node.getFc(), Level.FINE, "sa_iec61850_group_read_failed ied="
                        + name + " ref=" + g.node.getReference() + " fc=" + g.node.getFc() + " " + describe(e));
                for (Watched w : g.members) readSingle(w, cause);
                return;
            }
            synchronized (lock) {
                apply(g.node, copy);
                for (Watched w : g.members) {
                    loggedOnce.remove("rf|" + w.target.key());
                    emit(w, cause);
                }
            }
        }

        private void readSingle(Watched w, String cause) throws IOException {
            FcModelNode copy = (FcModelNode) w.owner.copy();
            try {
                call(() -> assoc.getDataValues(copy));
            } catch (ServiceError e) {
                synchronized (lock) {
                    if (loggedOnce.add("rf|" + w.target.key())) {
                        problem(w.target, Problem.READ_FAILED, describe(e));
                    }
                }
                return;
            }
            synchronized (lock) {
                apply(w.owner, copy);
                loggedOnce.remove("rf|" + w.target.key());
                emit(w, cause);
            }
        }

        /** Okunan kopyadaki degerleri yerel modele yazar (ayni yapi, ayni sira). */
        private void apply(FcModelNode modelNode, FcModelNode copy) {
            List<BasicDataAttribute> dst = modelNode.getBasicDataAttributes();
            List<BasicDataAttribute> src = copy.getBasicDataAttributes();
            for (int i = 0; i < Math.min(dst.size(), src.size()); i++) {
                BasicDataAttribute d = dst.get(i);
                BasicDataAttribute c = src.get(i);
                if (Objects.equals(d.getReference().toString(), c.getReference().toString())) d.setValueFrom(c);
            }
        }

        /** Hat canli mi: kucuk bir okuma. Yanit (hata bile olsa) gelirse canli. */
        private void probe() throws IOException {
            FcModelNode n = probeNode();
            if (n == null) {
                touch(); // okunacak dugum yok; canlilik denetlenemez
                return;
            }
            FcModelNode copy = (FcModelNode) n.copy();
            try {
                call(() -> assoc.getDataValues(copy));
            } catch (ServiceError e) {
                // yanit geldi: hat canli
            }
        }

        private FcModelNode probeNode() {
            synchronized (lock) {
                if (!groups.isEmpty() && !groups.get(0).members.isEmpty()) return groups.get(0).members.get(0).leaf;
            }
            for (ModelNode ld : model.getChildren()) {
                for (ModelNode ln : ld.getChildren()) {
                    for (ModelNode d : ln.getChildren()) {
                        if (d instanceof FcModelNode f && f.getFc() != Fc.CO && !(f instanceof Rcb)) return f;
                    }
                }
            }
            return null;
        }

        /** Kilit altinda cagrilir. */
        private void emit(Watched w, String cause) {
            if (stopped) return;
            listener.onSample(new Sample(w.target, w.leaf, Iec61850Values.good(w.q), Iec61850Values.validity(w.q),
                    Iec61850Values.time(w.t), cause));
        }
    }
}

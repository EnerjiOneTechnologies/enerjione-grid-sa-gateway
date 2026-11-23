package com.enerjione.sagateway.gateway;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.enerjione.sagateway.grid.GridClient;
import com.enerjione.sagateway.grid.GridClient.ValueItem;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

/**
 * Periyodik tekrar bastirma: raporsuz IED'nin yoklamasi, 104 devirli olcum ve
 * butunluk raporu degismemisse Grid'e gitmez; degisim, kalite degisimi,
 * kendiliginden rapor, genel sorgu ve baglanti kaybi her zaman gider. Bilgi
 * icin yenileme suresi dolunca ayni deger de gider. Kuyruk tasip deger duserse
 * bellek temizlenir (Grid eski degerde kalmaz).
 */
class TekrarBastirmaTest {

    private static SaGateway gateway(int kuyruk) {
        // Ag kullanilmaz: yalniz kuyruk davranisi sinanir (start() cagrilmaz).
        return new SaGateway(new GridClient("http://127.0.0.1:9/api/v1", "t"),
                new SaGateway.Options(60, 100, 1000, kuyruk, 0, ZoneOffset.UTC));
    }

    private static ValueItem v(long id, Double deger, String kalite, String sebep) {
        return new ValueItem(id, deger, kalite, null, sebep);
    }

    private static long bastirilan(SaGateway gw) {
        return gw.healthJson().path("suppressed").asLong();
    }

    @Test
    void degismeyenPeriyodikDegerGonderilmez() {
        SaGateway gw = gateway(1000);
        gw.enqueue(v(1, 5.0, "good", "interrogation"));
        gw.enqueue(v(1, 5.0, "good", "periodic"));
        gw.enqueue(v(1, 5.0, "good", "periodic"));
        assertEquals(1, gw.queueSize());
        assertEquals(2, bastirilan(gw));
        // Deger ya da kalite degisince gider.
        gw.enqueue(v(1, 6.0, "good", "periodic"));
        gw.enqueue(v(1, 6.0, "invalid", "periodic"));
        assertEquals(3, gw.queueSize());
    }

    @Test
    void kendiligindenGenelSorguVeBaglantiKaybiHerZamanGider() {
        SaGateway gw = gateway(1000);
        gw.enqueue(v(2, 1.0, "good", "periodic"));
        gw.enqueue(v(2, 1.0, "good", "spontaneous"));
        gw.enqueue(v(2, 1.0, "good", "interrogation"));
        gw.enqueue(v(2, null, "invalid", "comm_lost"));
        gw.enqueue(v(2, null, "invalid", "comm_lost"));
        assertEquals(5, gw.queueSize());
        assertEquals(0, bastirilan(gw));
    }

    @Test
    void noktalarBirbirindenBagimsiz() {
        SaGateway gw = gateway(1000);
        gw.enqueue(v(3, 0.0, "good", "periodic"));
        gw.enqueue(v(4, 0.0, "good", "periodic"));
        gw.enqueue(v(3, 0.0, "good", "periodic"));
        assertEquals(2, gw.queueSize());
    }

    @Test
    void yenilemeSuresiDolunceAyniDegerYineGider() throws InterruptedException {
        SaGateway gw = gateway(1000);
        gw.periodicRefreshMs(50);
        gw.enqueue(v(5, 1.0, "good", "periodic"));
        gw.enqueue(v(5, 1.0, "good", "periodic"));
        assertEquals(1, gw.queueSize());
        Thread.sleep(80);
        gw.enqueue(v(5, 1.0, "good", "periodic"));
        assertEquals(2, gw.queueSize());
    }

    @Test
    void kuyrukTasincaBellekTemizlenirVeDegerYenidenGider() {
        SaGateway gw = gateway(2);
        gw.enqueue(v(6, 1.0, "good", "periodic"));
        gw.enqueue(v(7, 1.0, "good", "periodic"));
        // Kuyruk dolu: en eski (6) duser, bellek temizlenir.
        gw.enqueue(v(8, 1.0, "good", "periodic"));
        assertEquals(1, gw.healthJson().path("dropped").asLong());
        // 6 Grid'e hic ulasmadi: ayni deger bastirilmadan yeniden kuyruga girer.
        gw.enqueue(v(6, 1.0, "good", "periodic"));
        assertEquals(0, bastirilan(gw));
    }

    @Test
    void silinenNoktaBellektenCikar() throws Exception {
        SaGateway gw = gateway(1000);
        gw.enqueue(v(9, 1.0, "good", "periodic"));
        // Yapilandirmada nokta 9 yok (IED'siz bos yapilandirma).
        gw.apply(new ObjectMapper().readTree("{\"config_version\":\"x\",\"ieds\":[]}"));
        gw.enqueue(v(9, 1.0, "good", "periodic"));
        assertEquals(0, bastirilan(gw));
        assertEquals(2, gw.queueSize());
    }
}

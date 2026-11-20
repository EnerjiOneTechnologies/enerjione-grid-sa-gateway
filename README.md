# EnerjiOne Grid SA Gateway

Trafo merkezi IED'lerinden (koruma röleleri, RTU) izleme verisini okur ve EnerjiOne Grid'in SA
modülüne gönderir. Komut göndermez; yalnız izleme yapar.

Grid'in gateway'lerinden (Modbus / DNP3 gateway) **bağımsızdır**:

- SA modülü kurulunca kendiliğinden kurulup çalışır.
- Kurulum başına tektir ve tüm SA IED'lerine hizmet eder.
- Grid'in Gateway'ler sayfasında görünmez; verisi SA ekranlarında görünür.

| Protokol | Durum |
|---|---|
| IEC 60870-5-104 | **Var.** İstemci bu depoda yazıldı, dış kütüphane yok (bkz. Lisans) |
| IEC 61850 | Sırada. IEC61850bean (Apache-2.0) ile IP'den model okuma ve raporlar |
| Modbus TCP | Planlı (j2mod, Apache-2.0) |
| DNP3 | Planlı (öncelik düşük) |

## 104 okuyucu (sahada tek başına)

Bir istasyona bağlanır, genel sorgu yapar ve gelen her nesneyi satır satır yazar. Grid'e hiçbir
şey göndermez. Devreye almada adres ve tip kontrolü için kullanılır.

```bash
docker run --rm ghcr.io/enerjionetechnologies/enerjione-grid-sa-gateway:<surum> \
  iec104 --host 10.0.0.5 --ca 1 --sure 60
# ya da: java -jar sa-gateway.jar iec104 --host 10.0.0.5 --ca 1 [--json]
```

Seçenekler: `--port 2404`, `--yedek IP`, `--gi-aralik SN`, `--k 12 --w 8 --t0 30 --t1 15 --t2 10 --t3 20`,
`--saat-dilimi UTC` (cihaz yerel saat gönderiyorsa `Europe/Istanbul`), `--sure SN` (0 = durdurulana
kadar), `--json`.

Örnek çıktı:

```
# baglandi 10.0.0.5:2404 CA 7, genel sorgu gonderildi
...  CA 7  IOA 1001  M_SP_NA_1  interrogation  deger 1  kalite iyi
...  CA 7  IOA 3002  M_ME_TF_1  spontaneous  deger 230.75  kalite iyi  zaman 2026-11-20T06:30:42.687Z
# genel sorgu tamamlandi
```

## 104 istemcisi

- **Bağlantı:** STARTDT ile açılır, ardından genel sorgu yapılır.
  - Genel sorgu istenirse belli aralıklarla tekrarlanır.
  - İstasyon yeniden başlarsa (M_EI_NA_1) genel sorgu yenilenir.
- **Standart kurallar:** t0–t3 zamanlayıcıları, k/w penceresi ve sıra numarası denetimi standarttaki gibidir. İhlalde bağlantı kapatılır ve yeniden kurulur.
- **Yeniden bağlanma:** 1 sn'den başlayıp 30 sn'ye kadar artan bekleme. Yedek adres varsa adresler sırayla denenir.
- **Desteklenen tipler:**
  - Zamansız: 1, 3, 5, 7, 9, 11, 13, 15, 21
  - Zamanlı: 30–37 (CP56Time2a)
  - Sistem: 70 (istasyon başladı), 100 (genel sorgu), 101 (sayaç sorgusu), 103 (saat)
  - Bilinmeyen tipteki ASDU atlanır ve günlüğe bir kez yazılır.
- **Kalite:** IV, NT ya da OV varsa değer `invalid`. SB, BL, sayaç taşması ve kademe geçiş bitleri korunur.
- **Zaman:** CP56Time2a etiketi saat dilimi taşımaz; varsayılan UTC kabul edilir (`E1_DEVICE_TIME_ZONE`).
- **Nokta eşleme:** (CA, IOA) üzerinden yapılır; tip kodu değil, tip ailesi karşılaştırılır.
  - Örnek: M_DP_NA_1 ve M_DP_TB_1 aynı `dp` noktasına gider. İstasyonlar genel sorguya zamansız, kendiliğinden değişime zamanlı tiple yanıt verir.
  - Aile uyuşmazsa değer yazılmaz ve günlüğe bir kez uyarı düşer.

## Gateway modu (`calis`)

Grid'den yapılandırmayı çeker, IED bağlantılarını kurar ve değerleri partiler halinde gönderir.

| Ortam değişkeni | Varsayılan | Anlam |
|---|---|---|
| `E1_GRID_URL` | — | Grid API'si, örn. `http://backend-api:8000/api/v1` |
| `E1_SA_TOKEN` / `E1_SA_TOKEN_FILE` | — | SA gateway belirteci (Grid üretir; modül kurulumu verir). Dosyada tutmak önerilir |
| `E1_CONFIG_REFRESH_SEC` | 60 | Yapılandırma yoklama aralığı |
| `E1_BATCH_MS` / `E1_BATCH_MAX` | 500 / 1000 | Parti bekleme süresi ve boyu |
| `E1_QUEUE_MAX` | 100000 | Grid'e ulaşılamazken bellekte tutulan değer |
| `E1_DEVICE_TIME_ZONE` | UTC | Cihaz zaman etiketlerinin saat dilimi |
| `E1_HEALTH_PORT` | 8030 | `GET /health` (0 = kapalı) |
| `E1_LOG_LEVEL` | INFO | |

Grid sözleşmesi (`/api/v1/sa/gateway/...`, kimlik `X-SA-Gateway-Token`):

- `GET config`: tüm SA IED'leri, bağlantı ayarları, noktalar ve adresler. `config_version`
  değişince yalnız değişen IED yeniden kurulur. Modül kapalıysa 404 döner; gateway bekler ve
  yoklamayı sürdürür.
- `POST values`: `{"values": [{"point_id", "value", "quality", "ts", "cause"}]}`.
  - `cause`: `spontaneous`, `interrogation`, `periodic`, `return`, `request` ya da `comm_lost`.
  - IED bağlantısı koparsa noktaları için `value: null, quality: invalid` gönderilir.
- **Teslim:** Grid'e ulaşamayan parti kaybolmaz; kuyruğun başına konup tekrar denenir. Kuyruk
  dolarsa en eski değer atılır ve sayılır.

## Geliştirme

JDK ya da Maven kurmadan Docker ile:

```bash
docker run --rm -v "$PWD:/src" -v e1-sa-m2:/root/.m2 -w /src maven:3.9.9-eclipse-temurin-21 mvn -B verify
```

Testler (`mvn verify`):

- **Çözücü:** tüm tipler, CP56 saat dilimi, bozuk ASDU.
- **Test istasyonuyla gerçek TCP üzerinden:** STARTDT, genel sorgu, w/t2 onayı, t3 TESTFR, t1 zaman aşımı, sıra hatası, hat kopması, yeniden başlayan istasyon, yedek adres, periyodik genel sorgu.
- **Uçtan uca:** test istasyonu → gateway → sahte Grid. Kapsananlar: Grid geçici hata verdiğinde değer kaybolmaması, yanlış belirteç, yapılandırma değişimi, kuyruk sınırı.

**Uyumluluk:** Grid'in `iec104-outbound` sunucusuna (bağımsız yazılmış Python 104 sunucusu) karşı
denendi. Genel sorgu, kendiliğinden ve zamanlı değerler, sayaç, kalite bitleri ve sunucu yeniden
başlayınca geri dönme doğru çalıştı. Henüz üçüncü taraf bir cihazda (RTU / röle) denenmedi.

## Lisans

Kapalı kaynak, EnerjiOne. Bağımlılık kuralı: yalnız Apache-2.0 ya da daha serbest lisans. GPL,
LGPL ve EPL olan dağıtılan jar'a girmez; JUnit (EPL) yalnız testte kullanılır.

104 için GPL'li j60870 ve lib60870 bu yüzden kullanılmadı, istemci burada yazıldı.

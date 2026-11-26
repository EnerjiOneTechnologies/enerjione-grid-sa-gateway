<p align="center">
  <picture>
    <source media="(prefers-color-scheme: dark)" srcset="docs/assets/logo-dark.png">
    <img src="docs/assets/logo.png" alt="EnerjiOne" width="360">
  </picture>
</p>

<h1 align="center">SA Gateway</h1>

<p align="center">
  Trafo merkezi IED'lerinden (koruma röleleri, RTU) izleme verisini okur<br>
  ve EnerjiOne Grid'in SA modülüne gönderir. Komut göndermez; <b>yalnız izler</b>.
</p>

<p align="center">
  <a href="https://github.com/EnerjiOneTechnologies/enerjione-grid-sa-gateway/actions/workflows/ci.yml"><img alt="CI" src="https://github.com/EnerjiOneTechnologies/enerjione-grid-sa-gateway/actions/workflows/ci.yml/badge.svg"></a>
  <a href="https://github.com/EnerjiOneTechnologies/enerjione-grid-sa-gateway/releases/latest"><img alt="Sürüm notları" src="https://img.shields.io/badge/s%C3%BCr%C3%BCm%20notlar%C4%B1-e97800"></a>
  <img alt="Java" src="https://img.shields.io/badge/java-21-0f172a">
  <img alt="Protokol" src="https://img.shields.io/badge/protokol-IEC%20104%20%C2%B7%20IEC%2061850-0f172a">
</p>

---

## Genel bakış

Grid'in gateway'lerinden (Modbus / DNP3 gateway) **bağımsızdır**:

- SA modülü kurulunca kendiliğinden kurulup çalışır.
- Kurulum başına tektir ve tüm SA IED'lerine hizmet eder.
- Grid'in Gateway'ler sayfasında görünmez; verisi SA ekranlarında görünür.

| Protokol | Durum |
|---|---|
| IEC 60870-5-104 | **Var.** İstemci bu depoda yazıldı, dış kütüphane yok (bkz. Lisans) |
| IEC 61850 | **Var.** MMS istemcisi, iec61850bean (Apache-2.0) üzerinde: IP'den model okuma, raporlar (BRCB/URCB), yoklama yedeği |
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

## 61850 okuyucu (sahada tek başına)

Bir IED'ye bağlanır. Grid'e hiçbir şey göndermez.

- **`--ref` yoksa:** IED'nin modelini IP üzerinden okur ve listeler. Liste mantıksal cihazları (LD), mantıksal düğümleri (LN), veri nesnelerini (DO) ve FC'lerini içerir; ardından veri kümelerini ve rapor bloklarını Grid biçiminde verir. Devreye almada adres bulmak için kullanılır.
- **`--ref` varsa:** verilen referansları okur ve değerleri geldikçe yazar. Değerler ilk okumadan, raporlardan ya da yoklamadan gelebilir.

```bash
docker run --rm ghcr.io/enerjionetechnologies/enerjione-grid-sa-gateway:<surum> \
  iec61850 --host 10.0.0.7
docker run --rm ghcr.io/enerjionetechnologies/enerjione-grid-sa-gateway:<surum> \
  iec61850 --host 10.0.0.7 --ref AA1E01A1LD0/XCBR1.Pos.stVal --fc ST \
  --ref AA1E01A1LD0/MMXU1.A.phsA.cVal.mag.f --fc MX --rcb AA1E01A1LD0/LLN0.RP.urcbMeas --sure 60
```

Seçenekler:

- `--port 102`
- `--ref` / `--fc`: tekrarlanabilir. Tek `--fc` verilirse tüm `--ref`'lere uygulanır.
- `--rcb`: tekrarlanabilir.
- `--yoklama MS`: periyodik okuma.
- `--gi-yok`: bağlanınca rapor bloklarında GI yapılmaz.
- `--ap-title 1,1,999,1,1` ve `--ae-qualifier 12`: IED'nin (called) değerleri.
- `--sure SN`: 0 = durdurulana kadar.
- `--json`

Örnek çıktı:

```
# baglandi 10.0.0.7:102, 2 LD
LD AA1E01A1LD0
  LN XCBR1
    Pos  [ST, CF]
...
# rapor bloklari (Grid bicimi)
AA1E01A1LD0/LLN0.BR.brcbStat01  veri_kumesi=AA1E01A1LD0/LLN0$StatDs
...  AA1E01A1LD0/XCBR1.Pos.stVal [ST]  DOUBLE_BIT_POS  spontaneous  deger ON (2)  kalite iyi  zaman 2026-11-21T08:15:30.500Z
```

## 61850 istemcisi

- **Bağlantı:** MMS, TCP 102.
  - Bağlantı ve istek yanıtı için zaman aşımı 10 sn.
  - `ap_title` / `ae_qualifier` verilirse IED tarafına (called) uygulanır. Verilmezse iec61850bean varsayılanı gider: 1,1,999,1,1 / 12.
  - Model (`retrieveModel`) her bağlantıda bir kez okunur.
- **Yeniden bağlanma:** 104'teki gibi, 1 sn'den 30 sn'ye kadar artan bekleme.
- **Yarı açık hat:** 30 sn hiç trafik olmazsa küçük bir okuma yapılır. Yanıt gelmezse bağlantı kopmuş sayılır. Böylece FIN/RST gelmeden ölen hat da fark edilir.
- **Nokta eşleme:** `(ref, fc)` modelde aranır.
  - Bulunamayan referans günlüğe bir kez yazılır (`sa_point_not_found`) ve Grid'e bir kez `invalid` gider.
  - Yaprak olmayan referans (ör. DO'nun kendisi) tip uyuşmazlığı sayılır ve atlanır.
- **İlk okuma:** bağlanınca tüm noktalar `interrogation` sebebiyle okunur.
  - Okumalar LN altındaki üst veri nesnesine göre gruplanır; ör. `MMXU1.A` tek istekle gelir.
  - Grup okunamazsa noktalar tek tek okunur.
- **Raporlar:** Grid'deki RCB adresi iki biçimde olabilir: `LD/LN.BR.ad` ya da `LD/LN$RP$ad`. BR tamponlu (BRCB), RP tamponsuz (URCB) bloktur.
  - **Örnek adı:** Ad modelde birebir yoksa aynı LN'deki numaralı örnekler sırayla denenir (`ad01`, `ad02`...). Birebir ad varken başka örneğe geçilmez; o örnek başka bir istemciye ayrılmış olabilir.
  - **Etkinleştirme sırası:** Bloğun değerleri okunur. Başka istemcide etkinse ya da veri kümesi modelde yoksa sonraki örneğe geçilir. URCB rezerve edilir. Tetikleyicilere veri değişimi, kalite değişimi ve GI eklenir. İsteğe bağlı alanlara sıra no, rapor zamanı, veri kümesi adı ve dahil olma sebebi eklenir. Ardından blok etkinleştirilir ve `gi_on_connect` ise GI yapılır.
  - **Ayar reddi:** IED ayar yazmayı reddederse bloktaki ayarla devam edilir.
  - **Etkinleşmeyen blok:** günlüğe bir kez yazılır, diğerleriyle devam edilir.
    - **Meşgul blok:** Blok başka istemcide olduğu için etkinleşmediyse (RptEna açık, rezervasyon ya da etkinleştirme reddi) 30 sn'de bir yeniden denenir.
    - **Geç etkinleşme:** `gi_on_connect` ise o blokta GI yapılır, değilse tüm noktalar okunur. Arada kaçan değişim olmaz.
    - **Yapısal hata:** Blok yoksa ya da veri kümesi boşsa veya modelde yoksa yeniden denenmez.
  - **Bırakma:** IED değişince, silinince ya da gateway dururken bağlantı tuttuğu blokları bırakır (RptEna=false, URCB rezervasyonu iptal), sonra kapanır. Yeni bağlantı aynı BRCB'yi ve aynı URCB örneğini alır.
    - Süre sınırlıdır: istek başına 1 sn, toplam 3 sn. Hat yanıt vermezse bırakmadan kapanır.
    - Beklenmedik kopmada bırakma yapılamaz; blokları IED'nin kendisi bırakmalıdır.
  - **Kalinan yerden surdurme (BRCB):** Her BRCB icin son alinan EntryID tutulur ve yeniden baglanirken etkinlestirmeden once yazilir; IED yalniz kopukken kacan girdileri yollar. Surdurulen blokta GI istenmez. Daha once tutulan ornek mesgulse 60 sn baska ornege gecilmez. EntryID bellektedir: gateway yeniden baslarsa tampon bastan gelir, Grid ayni zaman etiketli ayni degeri tekrar sayar.
  - **Rapor sebebi:** GI raporu `interrogation`, bütünlük (integrity) raporu `periodic`, diğerleri `spontaneous` olarak gider.
- **Yoklama yedeği:** `poll_fallback_ms > 0` ise tüm noktalar bu aralıkla okunur (`periodic`). Aralık turun başından sayılır. Aynı LN'deki aynı FC'li DO'lar tek istekle (`LN$FC`) okunur; IED bunu reddederse o LN DO başına okunur. Hiçbir blok etkinleşmese de yoklama çalışır. Rapor bloğu da yoklama da yoksa değerler yalnız bağlanınca okunur; günlüğe uyarı düşer.
- **Değer çevirme:**

| Nokta | 61850 tipi | Değer |
|---|---|---|
| `sp` | BOOLEAN | 1 / 0 |
| `dp` | Dbpos | 0 ara, 1 açık (off), 2 kapalı (on), 3 bozuk |
| `mv` | FLOAT32/64 ya da tamsayı | sayı; NaN / sonsuz → `null` + `invalid` |
| `it` | INT64 (BCR.actVal) ya da tamsayı | sayı |
| `st` | tamsayı / ENUM | sayı |
| `txt` | — | gönderilmez |

  Tip uymazsa değer yazılmaz ve günlüğe bir kez uyarı düşer.
- **Kalite ve zaman:** Yaprağı taşıyan veri nesnesinin (DO ya da SDO) `q` ve `t`'si kullanılır. Örneğin `X.Pos.stVal` için `X.Pos.q`, `X.A.phsA.cVal.mag.f` için `X.A.phsA.q` alınır.
  - **Validity:** GOOD → `good`. INVALID, QUESTIONABLE ve RESERVED → `invalid`. Test biti dikkate alınmaz.
  - **Zaman:** `t` UTC'dir (`E1_DEVICE_TIME_ZONE` 61850'ye uygulanmaz). Yazılmamış (1970) ya da saat arızalı (ClockFailure) etikette `ts` boş gider.
  - **Raporda olmayan q / t:** yerel modeldeki son bilinen değer kullanılır (son okuma ya da rapor). `q` hiç yoksa (ör. CF/DC) değer `good`, `ts` boş gider.
- **Bağlantı kaybı:** Düşüş anında tüm noktalar bir kez `value: null, quality: invalid, cause: comm_lost` gönderilir. Yeniden bağlanınca ilk okuma tekrarlanır.

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
  değişince yalnız değişen IED yeniden kurulur.
  - **404 / 403:** Modül kapalıysa 404, lisans geçersizse 403 döner. Gateway bu durumda tüm IED bağlantılarını kapatır, boşta bekler ve yoklamayı normal aralıkta sürdürür. Geçiş günlüğe bir kez yazılır (`sa_module_inactive`). Kuyruktaki değerler atılmaz; modül dönünce IED'lerin ilk okuması onları günceller.
  - **Diğer hatalar:** Ağ hatası ya da 5xx gibi durumlarda mevcut bağlantılar çalışmaya devam eder.
- `POST values`: `{"values": [{"point_id", "value", "quality", "ts", "cause"}]}`.
  - `cause`: `spontaneous`, `interrogation`, `periodic`, `return`, `request` ya da `comm_lost`.
  - IED bağlantısı koparsa noktaları için `value: null, quality: invalid` gönderilir.
- **Teslim:** Grid'e ulaşamayan parti kaybolmaz; kuyruğun başına konup tekrar denenir. Kuyruk
  dolarsa en eski değer atılır ve sayılır.
- **Tekrar bastırma:** `periodic` değer (rapor bloğu olmayan IED'nin yoklaması, 104 devirli ölçüm,
  bütünlük raporu) nokta için son gönderilenle aynıysa (değer ve kalite) gönderilmez; aynı değer
  10 dk'da bir yine gider. Kendiliğinden rapor, genel sorgu / ilk okuma ve bağlantı kaybı her zaman
  gider. Kuyruk taşıp değer düşerse bellek temizlenir, sonraki turda her nokta yeniden gider.
  Raporsuz 1116 sinyallik bir IED'de Grid'e giden değer saniyede ~220'den yalnız değişenlere indi.
- **Yoklama turu koruması:** 61850 yoklama turu aralıktan uzun sürerse sonraki tur en az tur süresi
  kadar beklenir (IED'ye ara vermeden istek gitmez); `sa_poll_slow` bir kez günlüğe yazılır.

### Keşif işleri (Grid 2.120+)

Grid, gateway'e iş verebilir. İlk iş türü IED keşfidir: Grid arayüzü bir IP'deki 61850 IED'sinin modelini sahaya gitmeden okur.

- `GET jobs`: Ayrı bir iş parçacığı 3 sn'de bir yoklar. Aynı anda tek iş çalışır. Keşif, yapılandırma yoklamasını ve değer gönderimini bekletmez.
- **`iec61850_discover`:** `params` = `{host, port, ap_title, ae_qualifier, timeout_ms}`; null değer varsayılan demektir.
  - Okuyucunun keşfiyle aynıdır: IED'ye bağlanır, modeli ve RCB değerlerini okur, kapatır.
  - Keşif bir izleme bağlantısı değildir; RCB etkinleştirilmez.
- `POST jobs/{id}/result`: `{"ok": true, "result": {logical_devices, nodes, datasets, rcbs, truncated}}` ya da `{"ok": false, "error": "..."}`.
  - `nodes`: FC'si ST ya da MX olan yapraklar (`ref`, `fc`, `type`). En fazla 50 000 düğüm gider; aşılırsa `truncated: true`.
  - `type` iec61850bean tip adıdır. Test sunucusunda görülenler: Dbpos `DOUBLE_BIT_POS`, BOOLEAN `BOOLEAN`, FLOAT32 `FLOAT32`, INT32 `INT32`, INT64 `INT64`, Enum `INT8`, Quality `QUALITY`, Timestamp `TIMESTAMP`.
  - `datasets` noktalı biçimdedir. `rcbs` Grid biçimindedir (`LD/LN.BR.ad`, `LD/LN.RP.ad`); `dataset` ve `enabled` (şu anki RptEna) ile gelir.
  - `error` en fazla 300 karakterlik kısa bir özettir, ör. `baglanti kurulamadi: ConnectException: Connection refused`. Bilinmeyen iş türü `desteklenmeyen is turu` ile döner.
  - Sonuç 5xx ya da ağ hatasında iki kez daha denenir. 404 (iş bilinmiyor) ve 409 (iş bitti) tekrar denenmez.
- **İş ucu hatası:** 404 (eski Grid ya da modül kapalı), 401/403, 5xx ya da ağ hatası modülü kapatmaz. Yalnız iş yoklaması 60 sn'ye seyrelir; IED bağlantıları sürer.

### Sağlık

`GET /health` IED başına `protocol`, `connected`, `points`, `reconnects` ve `last_error` verir.
Bunlara ek olarak 104'te `asdu`, 61850'de `reports`, `rcb_enabled` (şu an etkin blok sayısı) ve
yoklama varsa `poll_ms` (son yoklama turunun süresi) gelir. `last_error` bağlantı kurulunca temizlenir.

Genel alanlarda `module_active`, `suppressed` (gönderilmeyen tekrar sayısı) ve `jobs` da bulunur. `jobs`: `last_id`, `last_status` (`ok`, `error`, `rejected`, `post_failed`), `last_at`, `poll_error`.

## Geliştirme

JDK ya da Maven kurmadan Docker ile:

```bash
docker run --rm -v "$PWD:/src" -v e1-sa-m2:/root/.m2 -w /src maven:3.9.9-eclipse-temurin-21 mvn -B verify
```

Testler (`mvn verify`):

- **Çözücü:** tüm tipler, CP56 saat dilimi, bozuk ASDU.
- **Test istasyonuyla gerçek TCP üzerinden:** STARTDT, genel sorgu, w/t2 onayı, t3 TESTFR, t1 zaman aşımı, sıra hatası, hat kopması, yeniden başlayan istasyon, yedek adres, periyodik genel sorgu.
- **Uçtan uca:** test istasyonu → gateway → sahte Grid. Kapsananlar: Grid geçici hata verdiğinde değer kaybolmaması, yanlış belirteç, yapılandırma değişimi, kuyruk sınırı.
- **Modül kapalı:** sahte Grid 404 ya da 403 döndüğünde tüm IED'ler kapanır, 200'de geri gelir. 500'de bağlantılar korunur.
- **İş kanalı:** keşif sonucu (tipler, veri kümesi, bloklar), kapalı port / bilinmeyen tür / eksik host hatası, düğüm sınırı. İş ucu 404 döndüğünde modül kapanmaz, yoklama seyrelir.
- **61850, süreç içi iec61850bean sunucusuyla:** Sunucu küçük bir SCL test dosyasından (`src/test/resources/iec61850/e1test.icd`) kurulur. Kapsananlar:
  - ilk okumada tüm tipler
  - rapordan gelen değişim (`spontaneous`, zaman ve kalite ile)
  - geçersiz ve şüpheli kalite, NaN ölçüm
  - bulunamayan referans (bir kez `invalid`)
  - RCB olmadan yoklama
  - sunucu durunca `comm_lost` ve yeniden bağlanma
  - numaralı URCB örneği; ikinci istemcinin sonraki örneği alması
  - BRCB ile URCB'nin birlikte etkinleşmesi
  - kapatınca, değişen ya da silinen IED'de blokların bırakılması; aynı sunucudaki yeni IED'nin aynı örnekleri alması
  - başka istemcideki bloğun bırakılınca yeniden denemede etkinleşmesi (okuma ve GI ile)
  - yarı açık hattın canlılık okumasıyla fark edilmesi
  - okuyucu (keşif ve izleme)
  - gateway üzerinden uçtan uca

**Uyumluluk:** Grid'in `iec104-outbound` sunucusuna (bağımsız yazılmış Python 104 sunucusu) karşı
denendi. Genel sorgu, kendiliğinden ve zamanlı değerler, sayaç, kalite bitleri ve sunucu yeniden
başlayınca geri dönme doğru çalıştı. Henüz üçüncü taraf bir cihazda (RTU / röle) denenmedi.

61850 yalnız iec61850bean'in kendi sunucusuna karşı denendi; gerçek bir IED'de (ABB, Siemens vb.)
henüz denenmedi. Bilinen sınırlar:

- **BRCB:** Test sunucusu tamponlu rapor göndermez. BRCB'nin bulunup etkinleştirildiği denendi, tamponlu rapor akışı denenmedi. Bağlantı kopunca biriken tampon kayıtlarının yeniden gönderimi (EntryID ile kaldığı yerden devam) yok.
- **Rapor sırası:** iec61850bean her raporu ayrı bir iş parçacığında verir. Arka arkaya çok hızlı gelen iki raporun sırası nadiren karışabilir; değer zaman etiketiyle (`ts`) gider.
- **Okuma ile rapor çakışması:** Okuma yanıtı yolda iken gelen daha yeni bir rapor, yerel modelde eski okuma değeriyle ezilebilir. Bir sonraki değişim, okuma ya da GI bunu düzeltir.
- **Model okuma:** iec61850bean model okumada veri kümesi listesinin devamını (more follows) desteklemez; çok sayıda veri kümesi olan bir LD'de bağlantı kurulamayabilir.
- **Eski bağlantı:** Beklenmedik kopmada (gateway çöktü, hat koptu) önceki bağlantının blokları IED bırakana kadar "başka istemcide" görünür; gateway onları 30 sn'de bir yeniden dener.
  - iec61850bean sunucusu kapanan bağlantının bloklarını hiç bırakmaz: BRCB'nin ve URCB'nin RptEna'sı açık kalır.
  - Bu sunucuda blokları yalnız bilerek durdurmadaki bırakma boşaltır.

## Lisans

Kapalı kaynak, EnerjiOne. Bağımlılık kuralı: yalnız Apache-2.0 ya da daha serbest lisans. GPL,
LGPL ve EPL olan dağıtılan jar'a girmez; JUnit (EPL) yalnız testte kullanılır.

104 için GPL'li j60870 ve lib60870 bu yüzden kullanılmadı, istemci burada yazıldı.

Dağıtılan jar'daki bağımlılıklar:

| Kütüphane | Sürüm | Lisans |
|---|---|---|
| jackson-databind (+ core, annotations) | 2.18.2 | Apache-2.0 |
| iec61850bean | 1.9.0 | Apache-2.0 |
| asn1bean | 1.12.0 | Apache-2.0 |
| slf4j-api | 1.7.25 | MIT |

iec61850bean'in pom'u `logback-classic`'i (EPL-1.0 / LGPL-2.1) runtime olarak çeker; `pom.xml`'de
hariç tutulur. İstemci yolu slf4j'ye hiç yazmadığı için bir slf4j bağlayıcısı da eklenmedi.

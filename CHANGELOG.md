# Degisiklik gunlugu

## [Yayinlanmamis]

### Eklendi

- Grid is kanali (Grid 2.120+): `GET /sa/gateway/jobs` ayri bir is parcaciginda 3 sn'de bir yoklanir; ayni anda tek is calisir.
  - `iec61850_discover`: IED'nin modeli ve RCB degerleri okunur (RCB etkinlestirilmez); sonuc `POST /sa/gateway/jobs/{id}/result` ile gider.
  - Dugumler yalniz ST / MX yapraklaridir (en fazla 50 000, asilirsa `truncated`). Veri kumeleri noktali, RCB adresi Grid bicimindedir.
  - Hata `ok: false` ve kisa ozetle (en fazla 300 karakter) doner; bilinmeyen tur "desteklenmeyen is turu" ile doner.
  - Is ucunun 404 / 401 / 403 / 5xx / ag hatasi modulu kapatmaz; yalniz is yoklamasi 60 sn'ye seyrelir.
- `/health`: `jobs` alani (`last_id`, `last_status`, `last_at`, `poll_error`).

### Duzeltildi

- 61850: IED degisince, silinince ya da gateway dururken RCB'ler birakilir (RptEna=false, URCB rezervasyon iptali), sonra iliski kapanir. Onceden yeni iliski BRCB'yi "baska bir istemcide etkin" gorup etkinlestiremiyor, URCB sonraki ornege (urcbMeas02) kayiyordu.
- 61850: Baska istemcide oldugu icin etkinlesmeyen RCB 30 sn'de bir yeniden denenir. Gec etkinlesmede GI yapilir ya da tum noktalar okunur. Yapisal hatalar (blok yok, veri kumesi bos / modelde yok) yeniden denenmez.
- 61850: `/health` `last_error` baglanti kurulunca temizlenir; `rcb_enabled` anlik sayiyi gosterir.

### Degisti

- Gateway IED'leri paralel kapatir; degisen IED'nin yenisi eskisi kapandiktan sonra baslar.

## [0.2.0] - 2026-11-23

### Eklendi

- IEC 61850 MMS istemcisi (yalniz izleme), iec61850bean 1.9.0 (Apache-2.0) uzerinde:
  - Model her baglantida `retrieveModel()` ile okunur.
  - Baglaninca ilk okuma yapilir; okumalar ust veri nesnesine gore gruplanir.
  - BRCB / URCB raporlari desteklenir. RCB adi modelde birebir yoksa numarali ornekler (ad01, ad02...) denenir. URCB rezerve edilir; tetikleyici ve istege bagli alanlar ayarlanir; istenirse GI yapilir.
  - `poll_fallback_ms` ile yoklama yedegi vardir.
  - Kalite ve zaman, yapragin veri nesnesindeki `q` / `t`'den alinir.
  - Baglanti kaybinda `comm_lost` gider; 1-30 sn artan beklemeyle yeniden baglanilir.
  - Yari acik hat canlilik okumasiyla fark edilir.
- Saha okuyucusu `iec61850`: ref verilmezse IP'den model kesfi yapar (LD / LN / DO, veri kumeleri, rapor bloklari). Ref verilirse degerleri geldikce yazar.
- `/health`: IED basina `protocol`. 61850 icin `reports` ve `rcb_enabled` eklendi; genel `module_active` alani eklendi.

### Degisti

- Protokol isleyicileri ortak bir arayuzde (`IedHandler`) toplandi. Gateway `iec104` ve `iec61850` IED'lerini birlikte calistirir; diger protokoller bir kez gunluge yazilip atlanir.
- Yapilandirma 404 (SA modulu kapali) ya da 403 (lisans gecersiz) donerse tum IED baglantilari kapatilir. Gateway bosta bekler, yoklama surer; kuyruktaki degerler atilmaz. Gecis bir kez gunluge yazilir (`sa_module_inactive`). Ag hatasi ve 5xx'te mevcut baglantilar eskisi gibi calisir.
- `logback-classic` (EPL / LGPL) iec61850bean bagimliligindan haric tutuldu; dagitilan jar'da yok.

## [0.1.0] - 2026-11-20

### Eklendi

- IEC 60870-5-104 istemcisi (yalniz izleme) ve sahada kullanilan 104 okuyucu (`iec104`).
- Gateway modu (`calis`): Grid SA uclarindan yapilandirma, deger partileri, baglanti kaybinda gecersiz kalite, `/health`.
- Grid'in gateway'lerinden bagimsiz: SA modulu ile kurulur, kurulum basina tek; kimlik modul belirteci (`X-SA-Gateway-Token`, Grid 2.118+).
- NaN / sonsuz olcum gecersiz (`null`) gonderilir.
- Imaj yayini: `ghcr.io/enerjionetechnologies/enerjione-grid-sa-gateway` (`v*.*.*` etiketiyle).

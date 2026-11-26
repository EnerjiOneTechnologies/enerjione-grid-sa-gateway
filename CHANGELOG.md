# Degisiklik gunlugu

## [Yayinlanmamis]

## [0.4.0] - 2026-11-26

### Duzeltildi

- 61850 BRCB tekrari: yeniden baglanmada IED tamponu bastan yolluyordu (EntryID yazilmiyordu); gecmise yinelenen ve eski tarihli olaylar dusuyor, canli deger kisa sure eski degerlere donuyordu. Artik her BRCB icin son alinan EntryID tutulur (IED ayari degisse de surec boyunca) ve etkinlestirmeden once yazilir: IED yalniz kopukken kacan girdileri yollar (`sa_brcb_resumed`; IED reddederse `sa_brcb_resume_rejected`, tampon bastan gelir). BRCB'de OptFlds entryID acilir. Surdurulen BRCB'de GI istenmez (gozlem: GI'li denemede libiec61850 bir tampon girdisini atladi, GI'siz turlarda atlamadi); guncel deger baglanirken okumayla gelir.
- Bilinen: ayni veri kumesi hem URCB hem BRCB ile etkinse yeniden baglanmada URCB'nin canli raporlari BRCB'nin kacan girdilerinden once varir; Grid'de canli deger bir an eski degerlere doner ve en yeni olay iki kez yazilabilir. Ayni veri kumesi icin tek blok kullanin.
- Daha once tutulan BRCB ornegi mesgulse (IED onceki baglantiyi henuz dusurmedi) 60 sn baska ornege gecilmez (`sa_brcb_wait`): tampon o ornekte. Onceden hemen bos ornege (brc102) gecilip kopukken kacan olaylar kayboluyordu. Gateway sureci yeniden baslarsa tampon bastan gelir; Grid ayni zaman etiketli ayni degeri tekrar sayar (Grid tarafi).

### Degisti

- 61850 yoklamasi LN duzeyinde: ayni LN'deki ayni FC'li DO'lar tek MMS istegiyle (`LN$FC`) okunur. 1116 sinyallik simulatorde tur ~9 sn'den ~0,7 sn'ye indi (202 istek, onceden ~1000). LN okumasini reddeden IED'de o LN, yeniden baglanmalarda da DO basina okunur (`sa_iec61850_ln_read_failed` bir kez, FINE). LN okumasina hic yanit vermeyen IED'de (ust uste 2 oturum, oturumda basarili LN okumasi yokken zaman asimi) LN okumasi kapanir, DO basina okunur (`sa_iec61850_ln_read_off`); basarili LN okumasindan sonraki hat kopmasi sayilmaz.
- Yoklama araligi turun basindan sayilir: bir deger en gec bir aralik sonra yeniden okunur (onceden aralik + tur). Tur araliktan uzunsa koruma aynen: sonraki tur en az tur suresi kadar bekler.

## [0.3.0] - 2026-11-23

### Eklendi

- Grid is kanali (Grid 2.120+): `GET /sa/gateway/jobs` ayri bir is parcaciginda 3 sn'de bir yoklanir; ayni anda tek is calisir.
  - `iec61850_discover`: IED'nin modeli ve RCB degerleri okunur (RCB etkinlestirilmez); sonuc `POST /sa/gateway/jobs/{id}/result` ile gider.
  - Dugumler yalniz ST / MX yapraklaridir (en fazla 50 000, asilirsa `truncated`). Veri kumeleri noktali, RCB adresi Grid bicimindedir.
  - Hata `ok: false` ve kisa ozetle (en fazla 300 karakter) doner; bilinmeyen tur "desteklenmeyen is turu" ile doner.
  - Is ucunun 404 / 401 / 403 / 5xx / ag hatasi modulu kapatmaz; yalniz is yoklamasi 60 sn'ye seyrelir.
- `/health`: `jobs` alani (`last_id`, `last_status`, `last_at`, `poll_error`).
- Tekrar bastirma: `periodic` deger (raporsuz IED yoklamasi, 104 devirli olcum, butunluk raporu) son gonderilenle ayniysa Grid'e gitmez; ayni deger 10 dk'da bir yine gider. Kendiliginden rapor, genel sorgu ve baglanti kaybi her zaman gider; kuyruk tasip deger duserse bellek temizlenir. Raporsuz 1116 sinyallik IED'de Grid'e giden deger saniyede ~220'den yalniz degisenlere indi. `/health` `suppressed`.
- 61850 yoklama turu korumasi: tur araliktan uzun surerse sonraki tur en az tur suresi kadar bekler (`sa_poll_slow` bir kez); `/health` `poll_ms`.

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

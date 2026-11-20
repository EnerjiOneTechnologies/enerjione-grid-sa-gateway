# Degisiklik gunlugu

## [Yayinlanmamis]

## [0.1.0] - 2026-11-20

### Eklendi

- IEC 60870-5-104 istemcisi (yalniz izleme) ve sahada kullanilan 104 okuyucu (`iec104`).
- Gateway modu (`calis`): Grid SA uclarindan yapilandirma, deger partileri, baglanti kaybinda gecersiz kalite, `/health`.
- Grid'in gateway'lerinden bagimsiz: SA modulu ile kurulur, kurulum basina tek; kimlik modul belirteci (`X-SA-Gateway-Token`, Grid 2.118+).
- NaN / sonsuz olcum gecersiz (`null`) gonderilir.
- Imaj yayini: `ghcr.io/enerjionetechnologies/enerjione-grid-sa-gateway` (`v*.*.*` etiketiyle).

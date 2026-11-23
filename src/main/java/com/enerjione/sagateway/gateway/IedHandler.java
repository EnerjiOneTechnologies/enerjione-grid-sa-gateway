package com.enerjione.sagateway.gateway;

/**
 * Gateway'in calistirdigi tek bir IED baglantisi (protokolden bagimsiz).
 * {@link SaGateway} yapilandirmaya gore her IED icin bir tane kurar; parmak izi
 * degisince kapatip yeniden kurar. {@code /health} bu arayuz uzerinden okur.
 */
public interface IedHandler extends AutoCloseable {

    /**
     * Anlik durum.
     *
     * @param messages protokol mesaj sayaci (104: ASDU, 61850: rapor)
     * @param reportsEnabled etkin rapor kontrol blogu sayisi (104'te 0)
     */
    record Status(boolean connected, String host, long messages, long reconnects, String lastError,
                  int reportsEnabled) {
    }

    /** Grid'deki IED kodu (gunluk ve saglik icin). */
    String code();

    /** Grid protokol adi: {@code iec104}, {@code iec61850}. */
    String protocol();

    /** Baglanti + nokta yapilandirmasinin ozeti; degisirse IED yeniden kurulur. */
    String fingerprint();

    /** Eslenen Grid noktasi sayisi. */
    int pointCount();

    /** Baglanti is parcacigini baslatir (hemen doner). */
    void start();

    Status status();

    /** Baglantiyi kapatir; kapandiktan sonra kuyruga deger yazilmaz. */
    @Override
    void close();
}

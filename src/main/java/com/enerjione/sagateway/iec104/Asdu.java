package com.enerjione.sagateway.iec104;

import java.util.List;

/**
 * Cozulmus ASDU. {@code type} bilinmeyen tipte null; o durumda
 * {@code objects} bostur ve yalniz baslik bilgisi vardir.
 */
public record Asdu(
        int typeCode,
        TypeId type,
        boolean sequence,
        int cause,
        boolean negative,
        boolean test,
        int originator,
        int commonAddress,
        List<InfoObject> objects) {

    // Iletim nedenleri (COT), IEC 60870-5-101 7.2.3
    public static final int COT_PERIODIC = 1;
    public static final int COT_BACKGROUND = 2;
    public static final int COT_SPONTANEOUS = 3;
    public static final int COT_INITIALIZED = 4;
    public static final int COT_REQUEST = 5;
    public static final int COT_ACTIVATION = 6;
    public static final int COT_ACTIVATION_CON = 7;
    public static final int COT_DEACTIVATION = 8;
    public static final int COT_DEACTIVATION_CON = 9;
    public static final int COT_ACTIVATION_TERM = 10;
    public static final int COT_RETURN_REMOTE = 11;
    public static final int COT_RETURN_LOCAL = 12;
    public static final int COT_INTERROGATED = 20;
    public static final int COT_COUNTER_INTERROGATED = 37;
    public static final int COT_UNKNOWN_TYPE = 44;
    public static final int COT_UNKNOWN_CAUSE = 45;
    public static final int COT_UNKNOWN_CA = 46;
    public static final int COT_UNKNOWN_IOA = 47;

    /** Genel sorgu (20) ya da grup sorgusu (21..36) yaniti mi. */
    public boolean interrogationResponse() {
        return cause >= COT_INTERROGATED && cause <= 36;
    }

    /** Grid'e tasinan neden: spontaneous / interrogation / periodic / return / other. */
    public String causeName() {
        if (cause == COT_SPONTANEOUS) return "spontaneous";
        if (interrogationResponse() || (cause >= COT_COUNTER_INTERROGATED && cause <= 41)) return "interrogation";
        if (cause == COT_PERIODIC || cause == COT_BACKGROUND) return "periodic";
        if (cause == COT_RETURN_REMOTE || cause == COT_RETURN_LOCAL) return "return";
        if (cause == COT_REQUEST) return "request";
        return "other";
    }

    public String typeName() {
        return type != null ? type.name() : "TIP-" + typeCode;
    }
}

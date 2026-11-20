package com.enerjione.sagateway.iec104;

import java.util.HashMap;
import java.util.Map;

/**
 * IEC 60870-5-101/104 tip tanimlari (yalniz izleme yonu + istasyonun
 * yanitladigi sistem komutlari).
 *
 * <p>{@code elementSize}: bilgi nesnesinin IOA HARIC bayt boyu. Boyu bilinmeyen
 * bir tip gelirse ASDU'nun geri kalani okunamaz; o ASDU atlanir (baglanti
 * kesilmez, cunku cerceve uzunlugu APCI'dan bilinir).
 */
public enum TypeId {
    M_SP_NA_1(1, Family.SINGLE, 1, false),
    M_DP_NA_1(3, Family.DOUBLE, 1, false),
    M_ST_NA_1(5, Family.STEP, 2, false),
    M_BO_NA_1(7, Family.BITSTRING, 5, false),
    M_ME_NA_1(9, Family.MEASURED, 3, false),
    M_ME_NB_1(11, Family.MEASURED, 3, false),
    M_ME_NC_1(13, Family.MEASURED, 5, false),
    M_IT_NA_1(15, Family.COUNTER, 5, false),
    M_ME_ND_1(21, Family.MEASURED, 2, false),
    M_SP_TB_1(30, Family.SINGLE, 8, true),
    M_DP_TB_1(31, Family.DOUBLE, 8, true),
    M_ST_TB_1(32, Family.STEP, 9, true),
    M_BO_TB_1(33, Family.BITSTRING, 12, true),
    M_ME_TD_1(34, Family.MEASURED, 10, true),
    M_ME_TE_1(35, Family.MEASURED, 10, true),
    M_ME_TF_1(36, Family.MEASURED, 12, true),
    M_IT_TB_1(37, Family.COUNTER, 12, true),
    M_EI_NA_1(70, Family.SYSTEM, 1, false),
    C_IC_NA_1(100, Family.SYSTEM, 1, false),
    C_CI_NA_1(101, Family.SYSTEM, 1, false),
    C_CS_NA_1(103, Family.SYSTEM, 7, false);

    /** Nokta tipi esleme ailesi. Grid nokta tipleri: sp, dp, st, mv, it. */
    public enum Family {
        SINGLE("sp"),
        DOUBLE("dp"),
        STEP("st"),
        BITSTRING("st"),
        MEASURED("mv"),
        COUNTER("it"),
        SYSTEM(null);

        /** Grid SA nokta tipi; SYSTEM icin null. */
        public final String pointType;

        Family(String pointType) {
            this.pointType = pointType;
        }
    }

    public final int code;
    public final Family family;
    public final int elementSize;
    public final boolean timeTagged;

    private static final Map<Integer, TypeId> BY_CODE = new HashMap<>();

    static {
        for (TypeId t : values()) {
            BY_CODE.put(t.code, t);
        }
    }

    TypeId(int code, Family family, int elementSize, boolean timeTagged) {
        this.code = code;
        this.family = family;
        this.elementSize = elementSize;
        this.timeTagged = timeTagged;
    }

    /** Bilinmeyen kod icin null. */
    public static TypeId of(int code) {
        return BY_CODE.get(code);
    }
}

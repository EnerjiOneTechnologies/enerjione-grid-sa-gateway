package com.enerjione.sagateway.iec61850;

import com.beanit.iec61850bean.Brcb;
import com.beanit.iec61850bean.Rcb;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Grid'deki rapor kontrol blogu (RCB) adresi.
 *
 * <p>Grid bicimi FC'yi de tasir: {@code AA1E01A1LD0/LLN0.BR.brcbStat01} ya da
 * MMS bicimiyle {@code REL1LD0/LLN0$RP$urcbMeas}. BR = tamponlu (BRCB), RP =
 * tamponsuz (URCB). iec61850bean ise FC'siz nesne referansi kullanir:
 * {@code AA1E01A1LD0/LLN0.brcbStat01}.
 *
 * @param ld mantiksal cihaz (LD) adi, IED adi dahil
 * @param ln mantiksal dugum (LN) adi, genelde LLN0
 * @param buffered BR ise true
 * @param name RCB adi
 */
public record RcbRef(String ld, String ln, boolean buffered, String name) {

    private static final Pattern GRID = Pattern.compile(
            "^([A-Za-z0-9_]{1,64})/([A-Za-z0-9_]{1,32})[.$](BR|RP)[.$]([A-Za-z0-9_]{1,64})$");

    /** Grid bicimini cozer; uymazsa {@link IllegalArgumentException}. */
    public static RcbRef parse(String s) {
        Matcher m = GRID.matcher(s == null ? "" : s.trim());
        if (!m.matches()) {
            throw new IllegalArgumentException("gecersiz RCB adresi: " + s
                    + " (beklenen LD/LN.BR.ad ya da LD/LN$RP$ad)");
        }
        return new RcbRef(m.group(1), m.group(2), "BR".equals(m.group(3)), m.group(4));
    }

    /**
     * bean RCB'sinin Grid adresi: {@code LD/LN.ad} -> {@code LD/LN.BR.ad} ya da
     * {@code LD/LN.RP.ad}. Ad bean'in gordugu gibidir (numarali ornek dahil).
     */
    public static RcbRef of(Rcb rcb) {
        String ref = rcb.getReference().toString();
        int slash = ref.indexOf('/');
        int dot = ref.lastIndexOf('.');
        return new RcbRef(ref.substring(0, slash), ref.substring(slash + 1, dot), rcb instanceof Brcb,
                ref.substring(dot + 1));
    }

    /** iec61850bean nesne referansi: {@code LD/LN.ad}. */
    public String beanReference() {
        return ld + "/" + ln + "." + name;
    }

    /** LN referansi: {@code LD/LN}. */
    public String lnReference() {
        return ld + "/" + ln;
    }

    /** Grid bicimi (nokta ile). */
    public String gridString() {
        return ld + "/" + ln + "." + (buffered ? "BR" : "RP") + "." + name;
    }

    /** Ayni LN'deki baska bir ornek adiyla (ornek: brcbStat -> brcbStat01). */
    RcbRef withName(String n) {
        return new RcbRef(ld, ln, buffered, n);
    }

    @Override
    public String toString() {
        return gridString();
    }
}

package com.enerjione.sagateway.iec61850;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Grid RCB adresi ve AP title cozumu. */
class RcbRefTest {

    @Test
    void nokta_bicimi_tamponlu() {
        RcbRef r = RcbRef.parse("AA1E01A1LD0/LLN0.BR.brcbStat01");
        assertEquals("AA1E01A1LD0", r.ld());
        assertEquals("LLN0", r.ln());
        assertTrue(r.buffered());
        assertEquals("brcbStat01", r.name());
        assertEquals("AA1E01A1LD0/LLN0.brcbStat01", r.beanReference());
        assertEquals("AA1E01A1LD0/LLN0", r.lnReference());
        assertEquals("AA1E01A1LD0/LLN0.BR.brcbStat01", r.gridString());
    }

    @Test
    void dolar_bicimi_tamponsuz() {
        RcbRef r = RcbRef.parse("REL1LD0/LLN0$RP$urcbMeas");
        assertFalse(r.buffered());
        assertEquals("REL1LD0/LLN0.urcbMeas", r.beanReference());
        assertEquals("REL1LD0/LLN0.RP.urcbMeas", r.gridString());
    }

    @Test
    void karisik_ayirac_ve_bosluk() {
        assertEquals("LD/LLN0.rcb", RcbRef.parse(" LD/LLN0.RP$rcb ").beanReference());
        assertTrue(RcbRef.parse("LD/LLN0$BR.rcb").buffered());
    }

    @Test
    void gecersiz_adresler_reddedilir() {
        for (String s : new String[] {null, "", "LD/LLN0.rcb", "LD/LLN0.XX.rcb", "LLN0.BR.rcb",
                "LD/LLN0.BR.", "LD/LLN0.br.rcb", "LD/LN-0.BR.rcb", "LD/LLN0.BR.rcb.extra"}) {
            assertThrows(IllegalArgumentException.class, () -> RcbRef.parse(s), String.valueOf(s));
        }
    }

    @Test
    void ap_title_cozumu() {
        assertArrayEquals(new int[] {1, 1, 1, 999, 1}, Iec61850Client.Settings.parseApTitle("1,1,1,999,1"));
        assertArrayEquals(new int[] {1, 3, 9999, 33}, Iec61850Client.Settings.parseApTitle(" 1.3.9999.33 "));
        assertNull(Iec61850Client.Settings.parseApTitle(null));
        assertNull(Iec61850Client.Settings.parseApTitle(" "));
        assertThrows(IllegalArgumentException.class, () -> Iec61850Client.Settings.parseApTitle("1,a,3"));
    }
}

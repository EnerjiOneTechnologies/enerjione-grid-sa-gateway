package com.enerjione.sagateway.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.enerjione.sagateway.iec61850.TestIed;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** 61850 saha okuyucusu: kesif listesi ve deger izleme. */
@Timeout(60)
class Iec61850ReaderTest {

    private TestIed ied;

    @BeforeEach
    void setUp() throws Exception {
        ied = new TestIed();
    }

    @AfterEach
    void tearDown() {
        ied.close();
    }

    private String run(String... args) throws Exception {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        PrintStream out = new PrintStream(buf, true, StandardCharsets.UTF_8);
        int code = Iec61850Reader.run(args, out);
        assertEquals(0, code, buf.toString(StandardCharsets.UTF_8));
        return buf.toString(StandardCharsets.UTF_8);
    }

    @Test
    void ref_yoksa_modeli_listeler() throws Exception {
        String out = run("iec61850", "--host", "127.0.0.1", "--port", String.valueOf(ied.port()));
        assertTrue(out.contains("LD E1TESTLD0"), out);
        assertTrue(out.contains("  LN XCBR1"), out);
        assertTrue(out.contains("    Pos  [ST, CF]"), out);
        assertTrue(out.contains("E1TESTLD0/LLN0.Ds1  (3 uye)"), out);
        assertTrue(out.contains("E1TESTLD0/LLN0.BR.brcbStat  veri_kumesi=E1TESTLD0/LLN0$Ds1"), out);
        assertTrue(out.contains("E1TESTLD0/LLN0.RP.urcbMeas02"), out);
    }

    @Test
    void refler_gelince_degerleri_yazar() throws Exception {
        String out = run("iec61850", "--host", "127.0.0.1", "--port", String.valueOf(ied.port()),
                "--ref", TestIed.LD + "/XCBR1.Pos.stVal", "--fc", "ST",
                "--ref", TestIed.LD + "/MMXU1.A.phsA.cVal.mag.f", "--fc", "MX",
                "--rcb", TestIed.LD + "/LLN0$RP$urcbMeas", "--sure", "2", "--json");
        assertTrue(out.contains("\"ref\":\"E1TESTLD0/XCBR1.Pos.stVal\""), out);
        assertTrue(out.contains("\"value\":\"ON\",\"numeric\":2.0"), out);
        assertTrue(out.contains("\"numeric\":12.5"), out);
        assertTrue(out.contains("etkin_rcb=1"), out);
    }

    @Test
    void ref_fc_eslesmesi() {
        assertEquals(2, Iec61850Reader.targets(List.of("A/B.C.d", "A/B.C.e"), List.of("st")).size());
        assertThrows(IllegalArgumentException.class, () -> Iec61850Reader.targets(List.of("A/B.C.d"), List.of()));
        assertThrows(IllegalArgumentException.class,
                () -> Iec61850Reader.targets(List.of("A/B.C.d", "A/B.C.e", "A/B.C.f"), List.of("ST", "MX")));
        assertThrows(IllegalArgumentException.class, () -> Iec61850Reader.targets(List.of("A/B.C.d"), List.of("XX")));
    }
}

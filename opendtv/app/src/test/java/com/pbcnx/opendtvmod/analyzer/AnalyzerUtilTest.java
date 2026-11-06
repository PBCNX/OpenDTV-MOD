package com.pbcnx.opendtvmod.analyzer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

import org.junit.Test;

import java.util.List;

import com.pbcnx.opendtvmod.ts.ServiceInfo;

/** The context-free analyzer helpers, no views or device needed. */
public class AnalyzerUtilTest {

    private static ServiceInfo service(int sid, String name) {
        ServiceInfo s = new ServiceInfo(sid);
        s.name = name;
        return s;
    }

    @Test
    public void findServiceMatchesTheIdAndReturnsNullOtherwise() {
        ServiceInfo a = service(1, "A");
        ServiceInfo b = service(2, "B");
        List<ServiceInfo> list = List.of(a, b);
        assertSame(b, AnalyzerUtil.findService(list, 2));
        assertNull(AnalyzerUtil.findService(list, 9));
        assertNull(AnalyzerUtil.findService(List.of(), 1));
    }

    @Test
    public void safeNeverReturnsNull() {
        assertEquals("", AnalyzerUtil.safe(null));
        assertEquals("x", AnalyzerUtil.safe("x"));
    }

    @Test
    public void hex4PadsAndClampsNegatives() {
        assertEquals("001A", AnalyzerUtil.hex4(0x1A));
        assertEquals("0000", AnalyzerUtil.hex4(-5));
        assertEquals("FFFF", AnalyzerUtil.hex4(0xFFFF));
    }
}

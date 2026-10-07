package com.kunukuntla.a11yinspector;

import org.junit.Test;
import java.util.LinkedHashMap;
import java.util.Map;
import static org.junit.Assert.*;

public class PopupDiagnosticsTest {
    @Test public void unchangedTreeProducesNoChanges() {
        Map<String, String> nodes = new LinkedHashMap<>();
        nodes.put("23:uid-5", "page");
        assertEquals("", PopupDiagnostics.changes(nodes, new LinkedHashMap<>(nodes)));
    }

    @Test public void capturesPopupArrivalCheckboxChangeAndRemoval() {
        Map<String, String> before = new LinkedHashMap<>(), after = new LinkedHashMap<>();
        before.put("box", "checked=false");
        before.put("old", "old node");
        after.put("box", "checked=true");
        after.put("cover", "empty backdrop");
        String diff = PopupDiagnostics.changes(before, after);
        assertTrue(diff.contains("~ box checked=true"));
        assertTrue(diff.contains("+ cover empty backdrop"));
        assertTrue(diff.contains("- old old node"));
    }

    @Test public void boundsVerboseChangesWithoutLosingOmittedCount() {
        Map<String, String> after = new LinkedHashMap<>();
        for (int i = 0; i < 65; i++) after.put("node" + i, "value");
        String diff = PopupDiagnostics.changes(new LinkedHashMap<>(), after);
        assertTrue(diff.contains("+ node59 value"));
        assertFalse(diff.contains("+ node60 value"));
        assertTrue(diff.contains("5 further changes omitted"));
    }
}

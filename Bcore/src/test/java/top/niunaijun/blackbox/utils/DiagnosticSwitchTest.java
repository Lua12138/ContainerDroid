package top.niunaijun.blackbox.utils;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class DiagnosticSwitchTest {

    @Test
    public void isTruthyAcceptsEnabledValuesWithWhitespaceAndCaseInsensitivity() {
        assertTrue(DiagnosticSwitch.isTruthy("1"));
        assertTrue(DiagnosticSwitch.isTruthy(" true "));
        assertTrue(DiagnosticSwitch.isTruthy("YES"));
        assertTrue(DiagnosticSwitch.isTruthy("On"));
    }

    @Test
    public void isTruthyExactPreservesCallersThatDidNotTrimHistorically() {
        assertTrue(DiagnosticSwitch.isTruthyExact("true"));
        assertFalse(DiagnosticSwitch.isTruthyExact(" true "));
    }

    @Test
    public void isTruthyRejectsDisabledOrUnknownValues() {
        assertFalse(DiagnosticSwitch.isTruthy(null));
        assertFalse(DiagnosticSwitch.isTruthy(""));
        assertFalse(DiagnosticSwitch.isTruthy("0"));
        assertFalse(DiagnosticSwitch.isTruthy("false"));
        assertFalse(DiagnosticSwitch.isTruthy("off"));
        assertFalse(DiagnosticSwitch.isTruthy("enabled"));
    }
}

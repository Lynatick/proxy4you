package tv.mistick.proxy;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class UpdateCheckerTest {

    @Test
    public void newerVersionsDetected() {
        assertTrue(UpdateChecker.isNewer("1.9.0", "1.8.4"));
        assertTrue(UpdateChecker.isNewer("1.10.0", "1.9.9"));
        assertTrue(UpdateChecker.isNewer("2.0", "1.99.99"));
    }

    @Test
    public void sameOrOlderVersionsRejected() {
        assertFalse(UpdateChecker.isNewer("1.8.4", "1.8.4"));
        assertFalse(UpdateChecker.isNewer("1.8.3", "1.8.4"));
        assertFalse(UpdateChecker.isNewer("1.8", "1.8.0"));
    }
}

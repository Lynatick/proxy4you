package tv.mistick.proxy;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.EnumSet;

public class RoutePolicyTest {

    @Test
    public void selectedGroupsRouteTheirHosts() {
        assertTrue(RoutePolicy.requiresProxy(EnumSet.of(RoutePolicy.Group.TELEGRAM), false, "api.telegram.org"));
        assertTrue(RoutePolicy.requiresProxy(EnumSet.of(RoutePolicy.Group.TELEGRAM), false, "web.telegram.org"));
        assertFalse(RoutePolicy.requiresProxy(EnumSet.of(RoutePolicy.Group.TELEGRAM), false, "youtube.com"));

        assertTrue(RoutePolicy.requiresProxy(EnumSet.of(RoutePolicy.Group.WHATSAPP), false, "web.whatsapp.com"));
        assertTrue(RoutePolicy.requiresProxy(EnumSet.of(RoutePolicy.Group.CHATGPT), false, "chatgpt.com"));
        assertTrue(RoutePolicy.requiresProxy(EnumSet.of(RoutePolicy.Group.GITHUB), false, "raw.githubusercontent.com"));
    }

    @Test
    public void everythingRoutesAll() {
        assertTrue(RoutePolicy.requiresProxy(EnumSet.noneOf(RoutePolicy.Group.class), true, "example.com"));
    }

    @Test
    public void emptySelectionRoutesNothing() {
        assertFalse(RoutePolicy.requiresProxy(EnumSet.noneOf(RoutePolicy.Group.class), false, "youtube.com"));
    }

    @Test
    public void normalizeStripsPortAndCase() {
        assertTrue(RoutePolicy.requiresProxy(EnumSet.of(RoutePolicy.Group.YOUTUBE), false, "WWW.YouTube.COM:443"));
        assertTrue(RoutePolicy.requiresProxy(EnumSet.of(RoutePolicy.Group.YOUTUBE), false, "youtube.com."));
        assertFalse(RoutePolicy.requiresProxy(EnumSet.of(RoutePolicy.Group.YOUTUBE), false, "youtube.com.evil.test"));
    }
}

package tv.mistick.proxy;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.List;

public class NodeSourcesTest {

    @Test
    public void parsesPlainList() {
        String text = "# comment\n"
                + "1.2.3.4:8080\n"
                + "5.6.7.8:1080\n"
                + "  \n"
                + "not-an-address\n"
                + "9.9.9.9:notaport\n";
        List<NodeStore.Node> nodes = NodeSources.parse(text, false);
        assertEquals(2, nodes.size());
        assertEquals("1.2.3.4", nodes.get(0).host);
        assertEquals(8080, nodes.get(0).port);
        assertTrue(!nodes.get(0).socks5);
    }

    @Test
    public void parsesSocksList() {
        List<NodeStore.Node> nodes = NodeSources.parse("10.0.0.1:1080\n", true);
        assertEquals(1, nodes.size());
        assertTrue(nodes.get(0).socks5);
    }
}

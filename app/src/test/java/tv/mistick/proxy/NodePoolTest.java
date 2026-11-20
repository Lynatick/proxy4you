package tv.mistick.proxy;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

public class NodePoolTest {

    private List<NodeStore.Node> nodes() {
        List<NodeStore.Node> list = new ArrayList<>();
        list.add(new NodeStore.Node("1.1.1.1", 1080, false));
        list.add(new NodeStore.Node("2.2.2.2", 1080, true));
        return list;
    }

    @Test
    public void failureOfCurrentSelectsNextNode() {
        NodePool pool = new NodePool(nodes());
        NodePool.Entry first = pool.current();
        assertNotNull(first);

        pool.recordFailure(first);

        assertNotNull("после сбоя пул не должен оставаться без ноды", pool.current());
        assertEquals("SOCKS5 · 2.2.2.2:1080", pool.current().label());
    }

    @Test
    public void emptyPoolHasNoCurrent() {
        NodePool pool = new NodePool(new ArrayList<>());
        assertNull(pool.current());
        assertEquals(0, pool.size());
    }
}

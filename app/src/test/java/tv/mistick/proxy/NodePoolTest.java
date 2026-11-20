package tv.mistick.proxy;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

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
    public void connectionFailureOnlyPausesNode() {
        NodePool pool = new NodePool(nodes());
        NodePool.Entry first = pool.current();
        pool.recordFailure(first);

        assertEquals("сбой соединения не исключает ноду", 0, pool.disabledCount());
        assertEquals(1, pool.except(NodePool.setOf(first), 5).size() + 0);
    }

    @Test
    public void emptyPoolHasNoCurrent() {
        NodePool pool = new NodePool(new ArrayList<>());
        assertNull(pool.current());
        assertEquals(0, pool.size());
    }

    @Test
    public void failedNodeIsExcludedUntilReload() {
        NodePool pool = new NodePool(nodes());
        NodePool.Entry first = pool.current();
        pool.recordFailureAndExclude(first);

        assertEquals(1, pool.disabledCount());
        for (NodePool.Entry entry : pool.ranked()) {
            assertNotEquals("сбойная нода не должна предлагаться", first, entry);
        }

        pool.recordFailureAndExclude(pool.current());
        assertEquals(2, pool.disabledCount());
        assertTrue("все исключены — кандидатов нет", pool.ranked().isEmpty());

        // Перезагрузка списка (новый пул) снимает исключения.
        NodePool reloaded = new NodePool(nodes());
        assertEquals(0, reloaded.disabledCount());
        assertNotNull(reloaded.current());
    }
}

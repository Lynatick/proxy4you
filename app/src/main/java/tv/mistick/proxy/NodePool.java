package tv.mistick.proxy;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Пул нод с состоянием: кто работает, кто в паузе после сбоя и с какой задержкой.
 *
 * Логика та же, что в macOS-версии: проверенная нода приоритетнее непроверенной,
 * быстрая — медленной, сбойная уходит в экспоненциальную паузу (30 с … 15 мин),
 * чтобы не тратить на неё попытки.
 */
final class NodePool {

    static final class Entry {
        final NodeStore.Node node;
        int failures;
        long lastFailureAt;
        int latencyMs = -1;
        boolean verified;

        Entry(NodeStore.Node node) {
            this.node = node;
        }

        boolean inCooldown(long now) {
            if (failures == 0) {
                return false;
            }
            long backoff = Math.min(900_000L, 30_000L << Math.min(failures - 1, 5));
            return now - lastFailureAt < backoff;
        }

        String label() {
            return node.host + ":" + node.port;
        }
    }

    private final List<Entry> entries = new ArrayList<>();
    private Entry current;

    NodePool(List<NodeStore.Node> nodes) {
        for (NodeStore.Node node : nodes) {
            entries.add(new Entry(node));
        }
        if (!entries.isEmpty()) {
            current = entries.get(0);
        }
    }

    synchronized Entry current() {
        return current;
    }

    synchronized void select(Entry entry) {
        current = entry;
    }

    synchronized int size() {
        return entries.size();
    }

    synchronized void recordSuccess(Entry entry, int latencyMs) {
        entry.failures = 0;
        entry.latencyMs = latencyMs;
        entry.verified = true;
        current = entry;
    }

    synchronized void recordFailure(Entry entry) {
        entry.failures++;
        entry.lastFailureAt = System.currentTimeMillis();
        entry.verified = false;
        if (entry == current) {
            current = null;
        }
    }

    /** Кандидаты на попытку: сначала текущая, затем лучшие по состоянию. */
    synchronized List<Entry> candidates(int limit) {
        List<Entry> result = new ArrayList<>();
        if (current != null && !current.inCooldown(System.currentTimeMillis())) {
            result.add(current);
        }
        for (Entry entry : ranked()) {
            if (result.size() >= limit) {
                break;
            }
            if (!result.contains(entry)) {
                result.add(entry);
            }
        }
        return result;
    }

    synchronized List<Entry> ranked() {
        long now = System.currentTimeMillis();
        List<Entry> alive = new ArrayList<>();
        for (Entry entry : entries) {
            if (!entry.inCooldown(now)) {
                alive.add(entry);
            }
        }
        if (alive.isEmpty()) {
            alive.addAll(entries);
        }
        Collections.sort(alive, (a, b) -> {
            if (a.verified != b.verified) {
                return a.verified ? -1 : 1;
            }
            int left = a.latencyMs < 0 ? Integer.MAX_VALUE : a.latencyMs;
            int right = b.latencyMs < 0 ? Integer.MAX_VALUE : b.latencyMs;
            if (left != right) {
                return Integer.compare(left, right);
            }
            return Integer.compare(a.failures, b.failures);
        });
        return alive;
    }

    /** Все ноды, кроме перечисленных: для перебора после отказа. */
    synchronized List<Entry> except(Set<Entry> excluded, int limit) {
        List<Entry> result = new ArrayList<>();
        for (Entry entry : ranked()) {
            if (excluded.contains(entry)) {
                continue;
            }
            result.add(entry);
            if (result.size() >= limit) {
                break;
            }
        }
        return result;
    }

    synchronized List<Entry> snapshot() {
        return new ArrayList<>(entries);
    }

    static Set<Entry> setOf(Entry... entries) {
        Set<Entry> result = new HashSet<>();
        Collections.addAll(result, entries);
        return result;
    }
}

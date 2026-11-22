package tv.mistick.proxy;

import android.util.Log;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Быстрый поиск рабочей ноды через уже запущенное ядро.
 *
 * Зачем: перебор пачек по 20 секунд — это минуты. Здесь ноды текущей пачки
 * проверяются параллельно через clash_api ядра (`/proxies/{tag}/delay`), а
 * найденная сразу включается селектором `pick` — без перезапуска ядра.
 */
final class FastSearch {

    private static final String TAG = "MiStickProxy";

    /** Сообщения о ходе поиска. */
    interface Listener {
        void onProgress(int checked, int total);

        void onFound(String tag, int delayMs);

        void onNothing(int total);

        /** Началась проверка пачки `batch` из `total`. */
        default void onBatch(int batch, int total) {
        }

        /** Поиск прерван пользователем. */
        default void onCancelled() {
        }
    }

    /** Проверка «поиск отменён». */
    interface Cancel {
        boolean cancelled();
    }

    /** Найденная нода. */
    static final class Hit {
        final String tag;
        final int delayMs;

        Hit(String tag, int delayMs) {
            this.tag = tag;
            this.delayMs = delayMs;
        }
    }

    private FastSearch() {
    }

    /**
     * Проверяет ноды параллельно и останавливается на первой рабочей.
     *
     * @param controllerPort порт clash_api ядра
     * @param tags           теги нод текущей пачки
     * @param concurrency    сколько проверок идёт одновременно
     */
    /** Синхронный поиск: null, если рабочих нод в наборе нет. */
    static Hit find(int controllerPort, List<String> tags, int concurrency, int timeoutMs,
                    Cancel cancelled) {
        if (controllerPort <= 0 || tags == null || tags.isEmpty()) {
            return null;
        }
        List<String> queue = new ArrayList<>(tags);
        AtomicInteger next = new AtomicInteger();
        AtomicBoolean finished = new AtomicBoolean(false);
        final Hit[] found = {null};
        int workers = Math.max(1, Math.min(concurrency, queue.size()));

        List<Thread> pool = new ArrayList<>();
        for (int worker = 0; worker < workers; worker++) {
            Thread thread = new Thread(() -> {
                while (!finished.get() && !cancelled.cancelled()) {
                    int index = next.getAndIncrement();
                    if (index >= queue.size()) {
                        return;
                    }
                    String tag = queue.get(index);
                    int delay = ClashApi.delay(controllerPort, tag, timeoutMs);
                    if (delay > 0 && finished.compareAndSet(false, true)) {
                        found[0] = new Hit(tag, delay);
                        return;
                    }
                }
            }, "fast-find-" + worker);
            thread.setDaemon(true);
            pool.add(thread);
            thread.start();
        }
        for (Thread thread : pool) {
            try {
                thread.join();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        Hit hit = found[0];
        if (hit != null && ClashApi.select(controllerPort, EngineConfig.PICK_TAG, hit.tag)) {
            Log.i(TAG, "быстрый поиск: " + hit.tag + " ответила за " + hit.delayMs + " мс, включена");
        } else if (hit != null) {
            Log.i(TAG, "быстрый поиск: " + hit.tag + " ответила, но переключить не удалось");
        }
        return hit;
    }

    static void run(int controllerPort, List<String> tags, int concurrency, Listener listener) {
        if (controllerPort <= 0 || tags == null || tags.isEmpty()) {
            listener.onNothing(0);
            return;
        }

        new Thread(() -> {
            List<String> queue = new ArrayList<>(tags);
            AtomicInteger next = new AtomicInteger();
            AtomicInteger checked = new AtomicInteger();
            AtomicBoolean finished = new AtomicBoolean(false);
            int total = queue.size();
            Log.i(TAG, "быстрый поиск: " + total + " нод, первая " + queue.get(0));
            int workers = Math.max(1, Math.min(concurrency, total));

            List<Thread> pool = new ArrayList<>();
            for (int worker = 0; worker < workers; worker++) {
                Thread thread = new Thread(() -> {
                    while (!finished.get()) {
                        int index = next.getAndIncrement();
                        if (index >= queue.size()) {
                            return;
                        }
                        String tag = queue.get(index);
                        int delay = ClashApi.delay(controllerPort, tag, 4000);
                        int done = checked.incrementAndGet();
                        if (delay > 0) {
                            if (finished.compareAndSet(false, true)) {
                                Log.i(TAG, "быстрый поиск: " + tag + " ответила за " + delay + " мс");
                                if (ClashApi.select(controllerPort, EngineConfig.PICK_TAG, tag)) {
                                    listener.onFound(tag, delay);
                                } else {
                                    listener.onFound(tag, delay);
                                }
                            }
                            return;
                        }
                        listener.onProgress(done, total);
                    }
                }, "fast-search-" + worker);
                thread.setDaemon(true);
                pool.add(thread);
                thread.start();
            }

            for (Thread thread : pool) {
                try {
                    thread.join();
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
            if (finished.compareAndSet(false, true)) {
                Log.i(TAG, "быстрый поиск: рабочих нод не найдено из " + total);
                listener.onNothing(total);
            }
        }, "fast-search").start();
    }
}

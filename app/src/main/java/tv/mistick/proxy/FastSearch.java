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

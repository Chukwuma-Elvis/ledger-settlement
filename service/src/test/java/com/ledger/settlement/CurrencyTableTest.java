package com.ledger.settlement;

import jdk.jfr.Recording;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordingFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntConsumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Lab 1 step 9: loaded once, safe under concurrent first use, and reading it never pins. */
class CurrencyTableTest {

    private static final int READERS = 2_000;

    @Test
    void loadsOnceAndConcurrentFirstReadersAllSeeTheSameCompleteTable() throws Exception {
        AtomicInteger fetches = new AtomicInteger();
        CurrencyTable table = new CurrencyTable(() -> {
            fetches.incrementAndGet();
            return Map.of("GBP", 2, "JPY", 0);
        });
        var failures = new ConcurrentLinkedQueue<Throwable>();
        AtomicInteger rejected = new AtomicInteger();

        onVirtualThreadsAtOnce(READERS, i -> {
            try {
                table.requireSupported(i % 2 == 0 ? "GBP" : "JPY");
                assertThatThrownBy(() -> table.requireSupported("XXX")).isInstanceOf(IllegalStateException.class);
                rejected.incrementAndGet();
            } catch (Throwable t) {
                failures.add(t);
            }
        });

        assertThat(fetches).as("downstream fetches").hasValue(1);
        assertThat(failures).isEmpty();
        assertThat(rejected).as("readers that saw XXX rejected").hasValue(READERS);
    }

    @Test
    void holdsAnImmutableSnapshotOfWhatWasFetched() {
        Map<String, Integer> fetched = new HashMap<>(Map.of("GBP", 2));
        CurrencyTable table = new CurrencyTable(() -> fetched);
        fetched.clear();

        table.requireSupported("GBP");
    }

    @Test
    void readingDoesNotPinButBlockingInAClassInitializerDoes(@TempDir Path dir) throws Exception {
        CurrencyTable table = new CurrencyTable(() -> Map.of("GBP", 2));

        assertThat(pinnedEvents(dir.resolve("reads.jfr"),
                () -> onVirtualThreadsAtOnce(READERS, i -> table.requireSupported("GBP")))).isEmpty();
        // Positive control with the defect's shape, so a pass above isn't the check being blind.
        assertThat(pinnedEvents(dir.resolve("control.jfr"),
                () -> Thread.ofVirtual().start(SlowStaticInit::touch).join())).isNotEmpty();
    }

    static final class SlowStaticInit {
        static {
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        static void touch() {
        }
    }

    private interface Work {
        void run() throws Exception;
    }

    private static List<RecordedEvent> pinnedEvents(Path file, Work work) throws Exception {
        try (Recording recording = new Recording()) {
            recording.enable("jdk.VirtualThreadPinned").withThreshold(Duration.ZERO).withStackTrace();
            recording.start();
            work.run();
            recording.stop();
            recording.dump(file);
        }
        return RecordingFile.readAllEvents(file).stream()
                .filter(e -> e.getEventType().getName().equals("jdk.VirtualThreadPinned")).toList();
    }

    private static void onVirtualThreadsAtOnce(int count, IntConsumer task) throws InterruptedException {
        CountDownLatch start = new CountDownLatch(1);
        List<Thread> threads = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            int n = i;
            threads.add(Thread.ofVirtual().start(() -> {
                try {
                    start.await();
                    task.accept(n);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }));
        }
        start.countDown(); // release every reader together
        for (Thread thread : threads) {
            thread.join();
        }
    }
}

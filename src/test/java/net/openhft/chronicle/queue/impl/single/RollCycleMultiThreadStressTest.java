package net.openhft.chronicle.queue.impl.single;

import net.openhft.chronicle.core.FlakyTestRunner;
import net.openhft.chronicle.core.Jvm;
import net.openhft.chronicle.core.io.*;
import net.openhft.chronicle.core.onoes.ExceptionKey;
import net.openhft.chronicle.core.onoes.LogLevel;
import net.openhft.chronicle.core.threads.ThreadDump;
import net.openhft.chronicle.core.time.SetTimeProvider;
import net.openhft.chronicle.queue.*;
import net.openhft.chronicle.queue.impl.RollingChronicleQueue;
import net.openhft.chronicle.threads.NamedThreadFactory;
import net.openhft.chronicle.wire.DocumentContext;
import net.openhft.chronicle.wire.ValueIn;
import net.openhft.chronicle.wire.ValueOut;
import org.jetbrains.annotations.NotNull;
import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static java.lang.Thread.currentThread;
import static java.lang.Thread.yield;
import static net.openhft.chronicle.core.io.Closeable.closeQuietly;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class RollCycleMultiThreadStressTest {
    static {
        Jvm.disableDebugHandler();
    }

    final long SLEEP_PER_WRITE_NANOS;
    final int TEST_TIME;
    final int ROLL_EVERY_MS;
    final int DELAY_READER_RANDOM_MS;
    final int DELAY_WRITER_RANDOM_MS;
    final int WRITE_ONE_THEN_WAIT_MS;
    final int CORES;
    final Random random;
    final int NUMBER_OF_INTS;
    final boolean PRETOUCH;
    final boolean READERS_READ_ONLY;
    final boolean DUMP_QUEUE;
    final boolean SHARED_WRITE_QUEUE;
    final boolean DOUBLE_BUFFER;
    private ThreadDump threadDump;
    private Map<ExceptionKey, Integer> exceptionKeyIntegerMap;
    final Logger LOG = LoggerFactory.getLogger(getClass());
    final ThreadLocal<SetTimeProvider> timeProvider = ThreadLocal.withInitial(() -> new SetTimeProvider());
    private ChronicleQueue sharedWriterQueue;

    static {
        Jvm.setExceptionHandlers(Jvm.fatal(), Jvm.warn(), null);
    }

    private PretoucherThread pretoucherThread;

    public RollCycleMultiThreadStressTest() {
        SLEEP_PER_WRITE_NANOS = Long.getLong("writeLatency", 30_000);
        TEST_TIME = Integer.getInteger("testTime", 2);
        ROLL_EVERY_MS = Integer.getInteger("rollEvery", 300);
        DELAY_READER_RANDOM_MS = Integer.getInteger("delayReader", 1);
        DELAY_WRITER_RANDOM_MS = Integer.getInteger("delayWriter", 1);
        WRITE_ONE_THEN_WAIT_MS = Integer.getInteger("writeOneThenWait", 0);
        CORES = Integer.getInteger("cores", Runtime.getRuntime().availableProcessors());
        random = new Random(99);
        NUMBER_OF_INTS = Integer.getInteger("numberInts", 18);//1060 / 4;
        PRETOUCH = Jvm.getBoolean("pretouch");
        READERS_READ_ONLY = Jvm.getBoolean("read_only");
        DUMP_QUEUE = Jvm.getBoolean("dump_queue");
        SHARED_WRITE_QUEUE = Jvm.getBoolean("sharedWriteQ");
        DOUBLE_BUFFER = Jvm.getBoolean("double_buffer");

        if (TEST_TIME > 2) {
            AbstractReferenceCounted.disableReferenceTracing();
            if (Jvm.isResourceTracing()) {
                throw new IllegalStateException("This test will run out of memory - change your system properties");
            }
        }
    }

    static boolean areAllReadersComplete(final int expectedNumberOfMessages, final List<Reader> readers) {
        boolean allReadersComplete = true;

        int count = 0;
        for (Reader reader : readers) {
            ++count;
            if (reader.lastRead < expectedNumberOfMessages - 1) {
                allReadersComplete = false;
//                System.out.printf("Reader #%d last read: %d%n", count, reader.lastRead);
            }
        }
        return allReadersComplete;
    }

    @Test
    public void stressTest() throws Exception {
        FlakyTestRunner.run(this::stress);
    }

    public void stress() throws Exception {
        assert warnIfAssertsAreOn();
        Jvm.disableDebugHandler();
        File file = DirectoryUtils.tempDir("stress");

        DirectoryUtils.deleteDir(file);

//        System.out.printf("Queue dir: %s at %s%n", file.getAbsolutePath(), Instant.now());
        final int numThreads = CORES;
        final int numWriters = numThreads / 4 + 1;
        final ExecutorService executorServicePretouch = Executors.newSingleThreadExecutor(
                new NamedThreadFactory("pretouch"));
        final ExecutorService executorServiceWrite = Executors.newFixedThreadPool(numWriters,
                new NamedThreadFactory("writer"));
        final ExecutorService executorServiceRead = Executors.newFixedThreadPool(numThreads - numWriters,
                new NamedThreadFactory("reader"));

        final AtomicInteger wrote = new AtomicInteger();
        final int expectedNumberOfMessages = (int) (TEST_TIME * 1e9 / SLEEP_PER_WRITE_NANOS) * Math.max(1, numWriters / 2);

//        System.out.printf("Running test with %d writers and %d readers, sleep %dns%n",
//                numWriters, numThreads - numWriters, SLEEP_PER_WRITE_NANOS);
//        System.out.printf("Writing %d messages with %dns interval%n", expectedNumberOfMessages,
//                SLEEP_PER_WRITE_NANOS);
//        System.out.printf("Should take ~%dms%n",
//                TimeUnit.NANOSECONDS.toMillis(expectedNumberOfMessages * SLEEP_PER_WRITE_NANOS) / (numWriters / 2));

        final List<Future<Throwable>> results = new ArrayList<>();
        final List<Reader> readers = new ArrayList<>();
        final List<Writer> writers = new ArrayList<>();

        if (READERS_READ_ONLY)
            try (ChronicleQueue roq = createQueue(file)) {

            }

        if (SHARED_WRITE_QUEUE)
            sharedWriterQueue = createQueue(file);

        if (PRETOUCH) {
            pretoucherThread = new PretoucherThread(file);
            executorServicePretouch.submit(pretoucherThread);
        }

        if (WRITE_ONE_THEN_WAIT_MS > 0) {
            final Writer tempWriter = new Writer(file, wrote, expectedNumberOfMessages);
            try (ChronicleQueue queue = writerQueue(file)) {
                tempWriter.write(queue.acquireAppender());
            }
            timeProvider.get().advanceMillis(1);
        }
        for (int i = 0; i < numThreads - numWriters; i++) {
            final Reader reader = new Reader(file, expectedNumberOfMessages);
            readers.add(reader);
            results.add(executorServiceRead.submit(reader));
        }
        if (WRITE_ONE_THEN_WAIT_MS > 0) {
            LOG.warn("Wrote one now waiting for {}ms", WRITE_ONE_THEN_WAIT_MS);
            //     Jvm.pause(WRITE_ONE_THEN_WAIT_MS);
            timeProvider.get().advanceMillis(WRITE_ONE_THEN_WAIT_MS);
        }

        for (int i = 0; i < numWriters; i++) {
            final Writer writer = new Writer(file, wrote, expectedNumberOfMessages);
            writers.add(writer);
            results.add(executorServiceWrite.submit(writer));
        }

        final long maxWritingTime = TimeUnit.SECONDS.toMillis(TEST_TIME + 5) + queueBuilder(file).timeoutMS();
        long startTime = System.currentTimeMillis();
        final long giveUpWritingAt = startTime + maxWritingTime;
        long nextRollTime = System.currentTimeMillis() + ROLL_EVERY_MS, nextCheckTime = System.currentTimeMillis() + 5_000;
        int i = 0;
        long now;
        while ((now = System.currentTimeMillis()) < giveUpWritingAt) {
            if (wrote.get() >= expectedNumberOfMessages)
                break;
            if (now > nextRollTime) {
                timeProvider.get().advanceMillis(1000);
                nextRollTime += ROLL_EVERY_MS;
            }
            if (now > nextCheckTime) {
                String readersLastRead = readers.stream().map(reader -> Integer.toString(reader.lastRead)).collect(Collectors.joining(","));
//                System.out.printf("Writer has written %d of %d messages after %dms. Readers at %s. Waiting...%n",
//                        wrote.get() + 1, expectedNumberOfMessages,
//                        i * 10, readersLastRead);
                readers.stream().filter(r -> !r.isMakingProgress()).findAny().ifPresent(reader -> {
                    if (reader.exception != null) {
                        throw new AssertionError("Reader encountered exception, so stopped reading messages",
                                reader.exception);
                    }
                    throw new AssertionError("Reader is stuck");

                });
                if (pretoucherThread != null && pretoucherThread.exception != null)
                    throw new AssertionError("Preloader encountered exception", pretoucherThread.exception);
                nextCheckTime = System.currentTimeMillis() + 10_000L;
            }
            i++;
            //  Jvm.pause(50);
            timeProvider.get().advanceMillis(50);
        }
        double timeToWriteSecs = (System.currentTimeMillis() - startTime) / 1000d;

        final StringBuilder writerExceptions = new StringBuilder();
        writers.stream().filter(w -> w.exception != null).forEach(w -> {
            writerExceptions.append("Writer failed due to: ").append(w.exception.getMessage()).append("\n");
        });

        assertTrue("Wrote " + wrote.get() + " which is less than " + expectedNumberOfMessages + " within timeout. " + writerExceptions,
                wrote.get() >= expectedNumberOfMessages);

        readers.stream().filter(r -> r.exception != null).findAny().ifPresent(reader -> {
            throw new AssertionError("Reader encountered exception, so stopped reading messages",
                    reader.exception);
        });

/*
        System.out.println(String.format("All messages written in %,.0fsecs at rate of %,.0f/sec %,.0f/sec per writer (actual writeLatency %,.0fns)",
                timeToWriteSecs, expectedNumberOfMessages / timeToWriteSecs, (expectedNumberOfMessages / timeToWriteSecs) / numWriters,
                1_000_000_000 / ((expectedNumberOfMessages / timeToWriteSecs) / numWriters)));
*/

        final long giveUpReadingAt = System.currentTimeMillis() + 20_000L;
        final long dumpThreadsAt = giveUpReadingAt - 5_000L;

        try {
            while (System.currentTimeMillis() < giveUpReadingAt) {
                results.forEach(f -> {
                    try {
                        if (f.isDone()) {
                            final Throwable exception = f.get();
                            if (exception != null) {
                                throw Jvm.rethrow(exception);
                            }
                        }
                    } catch (InterruptedException e) {
                        // ignored
                    } catch (ExecutionException e) {
                        throw Jvm.rethrow(e);
                    }
                });

                boolean allReadersComplete = areAllReadersComplete(expectedNumberOfMessages, readers);

                if (allReadersComplete) {
                    break;
                }

//                System.out.printf("Not all readers are complete. Waiting...%n");
                //     Jvm.pause(2000);
                timeProvider.get().advanceMillis(2000);
            }
            assertTrue("Readers did not catch up",
                    areAllReadersComplete(expectedNumberOfMessages, readers));

        } catch (Exception e) {
            e.printStackTrace();
        } finally {

            executorServiceRead.shutdown();
            executorServiceWrite.shutdown();
            executorServicePretouch.shutdown();

            if (!executorServiceRead.awaitTermination(10, TimeUnit.SECONDS))
                executorServiceRead.shutdownNow();

            if (!executorServiceWrite.awaitTermination(10, TimeUnit.SECONDS))
                executorServiceWrite.shutdownNow();

            Closeable.closeQuietly(pretoucherThread);

            if (!executorServicePretouch.awaitTermination(10, TimeUnit.SECONDS))
                executorServicePretouch.shutdownNow();

            closeQuietly(sharedWriterQueue);
            results.forEach(f -> {
                try {
                    final Throwable exception = f.get(100, TimeUnit.MILLISECONDS);
                    if (exception != null) {
                        exception.printStackTrace();
                    }
                } catch (InterruptedException | TimeoutException e) {
                    // ignored
                } catch (ExecutionException e) {
                    throw Jvm.rethrow(e);
                }
            });
        }

        IOTools.deleteDirWithFiles("stress");

    }

    private boolean warnIfAssertsAreOn() {
        Jvm.warn().on(getClass(), "Reminder: asserts are on");
        return true;
    }

    @NotNull
    SingleChronicleQueueBuilder queueBuilder(File path) {
        return SingleChronicleQueueBuilder.binary(path)
                .testBlockSize()
                .timeProvider(timeProvider.get())
                .doubleBuffer(DOUBLE_BUFFER)
                .rollCycle(RollCycles.TEST_SECONDLY);
    }

    @NotNull
    private ChronicleQueue createQueue(File path) {
        return queueBuilder(path).timeProvider(timeProvider.get()).build();
    }

    @NotNull
    private ChronicleQueue writerQueue(File path) {
        return sharedWriterQueue != null ? sharedWriterQueue : createQueue(path);
    }

    @Before
    public void multiCPU() {
        Assume.assumeTrue(Runtime.getRuntime().availableProcessors() > 1);
    }

    @Before
    public void before() {
        threadDump = new ThreadDump();
        exceptionKeyIntegerMap = Jvm.recordExceptions();
    }

    @After
    public void after() {
        threadDump.assertNoNewThreads();
        // warnings are often expected
        exceptionKeyIntegerMap.entrySet().removeIf(entry -> entry.getKey().level.equals(LogLevel.WARN));
        if (Jvm.hasException(exceptionKeyIntegerMap)) {
            Jvm.dumpException(exceptionKeyIntegerMap);
            fail();
        }
        Jvm.resetExceptionHandlers();
        AbstractReferenceCounted.assertReferencesReleased();
    }

    final class Reader implements Callable<Throwable> {
        final File path;
        final int expectedNumberOfMessages;
        volatile int lastRead = -1;
        volatile Throwable exception;
        int readSequenceAtLastProgressCheck = -1;

        Reader(final File path, final int expectedNumberOfMessages) {
            this.path = path;
            this.expectedNumberOfMessages = expectedNumberOfMessages;
        }

        boolean isMakingProgress() {
            if (readSequenceAtLastProgressCheck == -1) {
                return true;
            }

            final boolean makingProgress = lastRead > readSequenceAtLastProgressCheck;
            readSequenceAtLastProgressCheck = lastRead;

            return makingProgress;
        }

        @Override
        public Throwable call() {
            SingleChronicleQueueBuilder builder = queueBuilder(path);
            if (READERS_READ_ONLY)
                builder.readOnly(true);
            long last = System.currentTimeMillis();
            try (RollingChronicleQueue queue = builder.build();
                 ExcerptTailer tailer = queue.createTailer()) {

                int lastTailerCycle = -1;
                int lastQueueCycle = -1;
                final int millis = 10;//random.nextInt(DELAY_READER_RANDOM_MS);
             //   Jvm.pause(millis);
                timeProvider.get().advanceMillis(millis);
                while (lastRead != expectedNumberOfMessages - 1) {
                    if (Thread.currentThread().isInterrupted())
                        return null;
                    try (DocumentContext dc = tailer.readingDocument()) {
                        if (!dc.isPresent()) {
                            long now = System.currentTimeMillis();
                            if (now > last + 2000) {
                                if (lastRead < 0)
                                    throw new AssertionError("read nothing after 2 seconds");
//                                System.out.println(Thread.currentThread() + " - Last read: " + lastRead);
                                last = now;
                            }
                            continue;
                        }

                        int v = -1;

                        final ValueIn valueIn = dc.wire().getValueIn();

                        final long documentAcquireTimestamp = valueIn.int64();
                        if (documentAcquireTimestamp == 0L) {
                            throw new AssertionError("No timestamp");
                        }
                        for (int i = 0; i < NUMBER_OF_INTS; i++) {
                            v = valueIn.int32();
                            if (lastRead + 1 != v) {
//                                    System.out.println(dc.wire());
                                String failureMessage = "Expected: " + (lastRead + 1) +
                                        ", actual: " + v + ", pos: " + i + ", index: " + Long
                                        .toHexString(dc.index()) +
                                        ", cycle: " + tailer.cycle();
                                if (lastTailerCycle != -1) {
                                    failureMessage += ". Tailer cycle at last read: " + lastTailerCycle +
                                            " (current: " + (tailer.cycle()) +
                                            "), queue cycle at last read: " + lastQueueCycle +
                                            " (current: " + queue.cycle() + ")";
                                }
                                if (DUMP_QUEUE)
                                    DumpQueueMain.dump(queue.file(), System.out, Long.MAX_VALUE);
                                throw new AssertionError(failureMessage);
                            }
                        }
                        lastRead = v;
                        lastTailerCycle = tailer.cycle();
                        lastQueueCycle = queue.cycle();
                    }
                }
            } catch (Throwable e) {
                exception = e;
                LOG.info("Finished reader", e);
                return e;
            }

            LOG.info("Finished reader OK");
            return null;
        }
    }

    final class Writer implements Callable<Throwable> {

        final File path;
        final AtomicInteger wrote;
        final int expectedNumberOfMessages;
        volatile Throwable exception;

        Writer(final File path, final AtomicInteger wrote,
               final int expectedNumberOfMessages) {
            this.path = path;
            this.wrote = wrote;
            this.expectedNumberOfMessages = expectedNumberOfMessages;
        }

        @Override
        public Throwable call() {
            ChronicleQueue queue = writerQueue(path);
            try (final ExcerptAppender appender = queue.acquireAppender()) {
                final int millis = random.nextInt(DELAY_WRITER_RANDOM_MS);
                Jvm.pause(millis);
                timeProvider.get().advanceMillis(millis);
                final long startTime = System.nanoTime();
                int loopIteration = 0;
                while (true) {
                    final int value = write(appender);
                    if (currentThread().isInterrupted())
                        return null;
                    while (System.nanoTime() < (startTime + (loopIteration * SLEEP_PER_WRITE_NANOS))) {
                        if (currentThread().isInterrupted())
                            return null;
                        // spin
                    }
                    loopIteration++;

                    if (value >= expectedNumberOfMessages) {
                        LOG.info("Finished writer");
                        return null;
                    }
                }
            } catch (Throwable e) {
                LOG.info("Finished writer", e);
                exception = e;
                return e;
            } finally {
                if (queue != sharedWriterQueue)
                    queue.close();
            }
        }

        private int write(ExcerptAppender appender) {
            int value;
            try (DocumentContext writingDocument = appender.writingDocument()) {
                final long documentAcquireTimestamp = System.nanoTime();
                value = wrote.getAndIncrement();
                ValueOut valueOut = writingDocument.wire().getValueOut();
                // make the message longer
                valueOut.int64(documentAcquireTimestamp);
                for (int i = 0; i < NUMBER_OF_INTS; i++) {
                    valueOut.int32(value);
                }
                writingDocument.wire().padToCacheAlign();
            }
            return value;
        }
    }

    class PretoucherThread extends AbstractCloseable implements Callable<Throwable> {

        final File path;
        volatile Throwable exception;

        private ExcerptAppender appender0;

        PretoucherThread(File path) {
            this.path = path;
        }

        @SuppressWarnings("resource")
        @Override
        public Throwable call() {

            try (ChronicleQueue queue = queueBuilder(path).build()) {

                ExcerptAppender appender = queue.acquireAppender();
                appender0 = appender;
//                System.out.println("Starting pretoucher");
                while (!queue.isClosed()) {

                    if (currentThread().isInterrupted())
                        return null;

                    timeProvider.get().advanceMillis(50);

                    if (isClosed())
                        return null;

                    appender.pretouch();
                    yield();

                }
            } catch (ClosedIllegalStateException ignore) {
                // shutting down
                return null;
            } catch (Throwable e) {
                if (appender0 != null && appender0.isClosed())
                    return null;
                exception = e;
                return e;
            }
            return null;
        }

        @Override
        protected void performClose() {
            Closeable.closeQuietly(appender0);
        }
    }

    public static void main(String[] args) throws IOException, InterruptedException, Exception {
        try {
            new RollCycleMultiThreadStressTest().stress();
        } catch (Exception e) {
            throw Jvm.rethrow(e);
        }
    }
}
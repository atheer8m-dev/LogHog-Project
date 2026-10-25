package com.loghog;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStreamReader;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Scanner;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;

public class ProjectRunner {

    public static final int DEFAULT_BATCH_SIZE = 1000;

    public static void main(String[] args) {
        waitForProfiler(args);

        int batchSize = parseBatchSize(args);
        System.out.println("Using batch size: " + batchSize);

        Path logPath = Paths.get("server.log");
        Path badDataPath = Paths.get("bad_data.log");

        CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPLACE)
                .onUnmappableCharacter(CodingErrorAction.REPLACE);

        Semaphore batchSemaphore = new Semaphore(50);
        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        List<CompletableFuture<Void>> futures = new ArrayList<>();

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(Files.newInputStream(logPath), decoder));
             BufferedWriter badDataWriter = Files.newBufferedWriter(badDataPath, StandardCharsets.UTF_8,
                     StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {

            String line;
            List<String> currentBatch = new ArrayList<>(batchSize);

            while ((line = reader.readLine()) != null) {
                if (isCorrupt(line)) {
                    badDataWriter.write(line);
                    badDataWriter.newLine();
                    continue;
                }

                currentBatch.add(line);
                if (currentBatch.size() >= batchSize) {
                    List<String> batchToSend = new ArrayList<>(currentBatch);
                    currentBatch.clear();

                    batchSemaphore.acquire();
                    CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
                        try {
                            processBatch(batchToSend);
                        } finally {
                            batchSemaphore.release();
                        }
                    }, executor);
                    futures.add(future);
                }
            }

            if (!currentBatch.isEmpty()) {
                batchSemaphore.acquire();
                CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
                    try {
                        processBatch(currentBatch);
                    } finally {
                        batchSemaphore.release();
                    }
                }, executor);
                futures.add(future);
            }

            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            executor.shutdown();
        }

        System.out.println("Processing completed successfully.");
    }

    private static int parseBatchSize(String[] args) {
        if (args.length > 0 && args[0] != null && !args[0].startsWith("--")) {
            try {
                return Integer.parseInt(args[0]);
            } catch (NumberFormatException ignored) {}
        }
        String prop = System.getProperty("batch.size");
        if (prop != null) {
            try {
                return Integer.parseInt(prop);
            } catch (NumberFormatException ignored) {}
        }
        return DEFAULT_BATCH_SIZE;
    }

    private static boolean isCorrupt(String line) {
        return line.contains("CORRUPT_DATA_ENTRY") || line.isBlank();
    }

    private static void waitForProfiler(String[] args) {
        boolean waitEnabled = Arrays.asList(args).contains("--wait") || Boolean.getBoolean("profiler.wait");
        if (!waitEnabled) {
            return;
        }
        System.out.println("Attach profiler and press Enter to continue...");
        try (Scanner scanner = new Scanner(System.in)) {
            if (scanner.hasNextLine()) {
                scanner.nextLine();
            }
        } catch (NoSuchElementException | IllegalStateException e) {
        }
    }

    private static void processBatch(List<String> batch) {
    }
}
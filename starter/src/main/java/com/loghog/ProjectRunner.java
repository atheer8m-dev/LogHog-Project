package com.loghog;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import java.util.HashMap;
import java.util.Scanner;
import java.util.regex.Pattern;
import java.lang.reflect.Method;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class ProjectRunner {

    public static void main(String[] args) throws IOException {
        if (args.length != 1) {
            System.err.println("Usage: java --add-modules jdk.incubator.vector -jar <your-jar-file> <logFilePath>");
            System.exit(1);
        }

        Path logFile = Paths.get(args[0]);

        if (!Files.exists(logFile)) {
            System.err.println("Error: File not found at " + logFile);
            System.exit(1);
        }

        // STEP 2: Architect Pluggable Extractors (Reflection & Annotations)
        LogMetricsParser parser = new LogMetricsParser();
        Map<Pattern, Method> extractorMap = new HashMap<>();
        
        for (Method method : LogMetricsParser.class.getDeclaredMethods()) {
            Extractor extractor = method.getAnnotation(Extractor.class);
            if (extractor != null) {
                extractorMap.put(Pattern.compile(extractor.value()), method);
            }
        }
        System.out.println("Discovered " + extractorMap.size() + " extractors via Reflection.");

        long startTime = System.nanoTime();

        // STEP 4: Architect Async Processing with Virtual Threads & Batches (Producer-Consumer)
        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        List<CompletableFuture<Void>> futures = new ArrayList<>();
        List<String> batch = new ArrayList<>(1000);

        try (var linesStream = Files.lines(logFile)) {
            for (String line : (Iterable<String>) linesStream::iterator) {
                batch.add(line);
                if (batch.size() >= 1000) {
                    List<String> batchCopy = new ArrayList<>(batch);
                    futures.add(CompletableFuture.runAsync(() -> {
                        processBatch(batchCopy, extractorMap, parser);
                    }, executor));
                    batch.clear();
                }
            }
            if (!batch.isEmpty()) {
                futures.add(CompletableFuture.runAsync(() -> {
                    processBatch(batch, extractorMap, parser);
                }, executor));
            }
        }

        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
        executor.shutdown();

        long endTime = System.nanoTime();
        long totalExecutionTimeMs = (endTime - startTime) / 1_000_000;

        // --- Final Report ---
        System.out.println("--- LogHog Analysis Complete ---");
        System.out.println("Total ERROR logs found: " + parser.getErrorCount());
        System.out.println("Unique IP addresses causing errors: " + parser.getUniqueIpCount());
        System.out.println("Average latency for /checkout: " + parser.getAverageCheckoutLatencyVector() + " ms");

        System.out.println("\nTotal execution time: " + totalExecutionTimeMs + " ms");

        waitForProfiler();
    }

    private static void processBatch(List<String> batch, Map<Pattern, Method> extractorMap, LogMetricsParser parser) {
        for (String line : batch) {
            for (var entry : extractorMap.entrySet()) {
                Pattern pattern = entry.getKey();
                Method method = entry.getValue();
                if (pattern.matcher(line).find()) {
                    try {
                        method.invoke(parser, line);
                    } catch (Exception e) {
                        // ignore
                    }
                }
            }
        }
    }

    /**
     * Pauses the application, allowing time to attach VisualVM or another profiler.
     */
    private static void waitForProfiler() {
        System.out.println("\nProcessing complete. Press Enter to exit...");
        try (Scanner scanner = new Scanner(System.in)) {
            scanner.nextLine();
        }
    }
}
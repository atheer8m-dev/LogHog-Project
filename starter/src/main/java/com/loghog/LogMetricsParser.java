package com.loghog;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import jdk.incubator.vector.IntVector;
import jdk.incubator.vector.VectorOperators;
import jdk.incubator.vector.VectorSpecies;

public class LogMetricsParser {
    private final AtomicLong errorCount = new AtomicLong();
    private final Set<String> uniqueIps = ConcurrentHashMap.newKeySet();
    private final List<Integer> latencies = Collections.synchronizedList(new ArrayList<>());
    
    private static final Path BAD_DATA_PATH = Path.of("bad_data.log");
    private int badDataCount = 0;
    private static final int MAX_BAD_DATA_LINES = 1000;

    private static final Pattern IP_PATTERN = Pattern.compile("\\b\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\b");
    private static final Pattern CHECKOUT_LATENCY_PATTERN = Pattern.compile("latency(?:_ms)?[:=]?\\s*(\\d+)");

    private synchronized void writeBadData(String line) {
        if (badDataCount >= MAX_BAD_DATA_LINES) {
            return; 
        }
        try {
            Files.writeString(
                BAD_DATA_PATH,
                line + System.lineSeparator(),
                StandardOpenOption.CREATE,
                StandardOpenOption.APPEND
            );
            badDataCount++;
        } catch (IOException e) {
        }
    }

    @Extractor("ERROR")
    public void parseError(String line) {
        errorCount.incrementAndGet();
    }

    @Extractor("client")
    public void parseIp(String line) {
        try {
            Matcher matcher = IP_PATTERN.matcher(line);
            if (matcher.find()) {
                uniqueIps.add(matcher.group());
            }
        } catch (Exception e) {
            writeBadData(line);
        }
    }

    @Extractor("/checkout")
    public void parseCheckoutLatency(String line) {
        try {
            Matcher matcher = CHECKOUT_LATENCY_PATTERN.matcher(line);
            if (matcher.find()) {
                int latency = Integer.parseInt(matcher.group(1));
                latencies.add(latency);
            }
        } catch (Exception e) {
            writeBadData(line);
        }
    }

    public long getErrorCount() {
        return errorCount.get();
    }

    public int getUniqueIpCount() {
        return uniqueIps.size();
    }

    public List<Integer> getLatencies() {
        return latencies;
    }

    public double getAverageCheckoutLatencyVector() {
        if (latencies.isEmpty()) return 0.0;
        
        int[] data = latencies.stream().mapToInt(i -> i).toArray();
        VectorSpecies<Integer> SPECIES = IntVector.SPECIES_PREFERRED;
        int upperBound = SPECIES.loopBound(data.length);
        
        IntVector acc = IntVector.zero(SPECIES);
        int i = 0;
        for (; i < upperBound; i += SPECIES.length()) {
            IntVector v = IntVector.fromArray(SPECIES, data, i);
            acc = acc.add(v);
        }
        
        long sum = acc.reduceLanes(VectorOperators.ADD);
        
        for (; i < data.length; i++) {
            sum += data[i];
        }
        
        return (double) sum / data.length;
    }
}
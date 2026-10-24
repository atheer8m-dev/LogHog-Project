package com.loghog;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import jdk.incubator.vector.IntVector;
import jdk.incubator.vector.VectorSpecies;

public class LogMetricsParser {
    private int errorCount = 0;
    private final Set<String> uniqueIps = new HashSet<>();
    private final List<Integer> latencies = new ArrayList<>();
    private static final Path BAD_DATA_PATH = Path.of("bad_data.log");
    
    private int badDataCount = 0;
    private static final int MAX_BAD_DATA_LINES = 1000;

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
        errorCount++;
    }

    @Extractor("IP:")
    public void parseIp(String line) {
        try {
            Matcher matcher = Pattern.compile("\\b\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\b").matcher(line);
            if (matcher.find()) {
                uniqueIps.add(matcher.group());
            } else {
                writeBadData(line); 
            }
        } catch (Exception e) {
            writeBadData(line);
        }
    }

    @Extractor("/checkout")
    public void parseCheckoutLatency(String line) {
        try {
            Matcher matcher = Pattern.compile("latency[:=]?\\s*(\\d+)").matcher(line);
            if (matcher.find()) {
                int latency = Integer.parseInt(matcher.group(1));
                synchronized (latencies) {
                    latencies.add(latency);
                }
            } else {
                writeBadData(line); 
            }
        } catch (Exception e) {
            writeBadData(line);         }
    }

    public int getErrorCount() {
        return errorCount;
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
        
        long sum = 0;
        int i = 0;
        for (; i < upperBound; i += SPECIES.length()) {
            IntVector v = IntVector.fromArray(SPECIES, data, i);
            sum += v.reduceLanes(jdk.incubator.vector.VectorOperators.ADD);
        }
        
        for (; i < data.length; i++) {
            sum += data[i];
        }
        
        return (double) sum / data.length;
    }
}
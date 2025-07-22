package com.redhat.podmortem.parser.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.redhat.podmortem.common.model.analysis.EventContext;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@ApplicationScoped
public class ContextAnalysisService {

    private static final Logger log = LoggerFactory.getLogger(ContextAnalysisService.class);

    @ConfigProperty(name = "scoring.context.keywords-directory", defaultValue = "keywords")
    String keywordsDirectory;

    private Map<String, Double> keywordWeights = new HashMap<>();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @PostConstruct
    public void loadKeywords() {
        keywordWeights.clear();

        try {
            List<String> keywordFiles = findKeywordFiles();

            if (keywordFiles.isEmpty()) {
                log.warn("No keyword files found in directory '{}'", keywordsDirectory);
                return;
            }

            int totalKeywords = 0;
            for (String filename : keywordFiles) {
                int loaded = loadKeywordFile(filename);
                totalKeywords += loaded;
                log.info("Loaded {} keywords from '{}'", loaded, filename);
            }

            log.info(
                    "Total keywords loaded: {} from {} files in directory '{}'",
                    totalKeywords,
                    keywordFiles.size(),
                    keywordsDirectory);
            log.debug("All keyword weights: {}", keywordWeights);

        } catch (Exception e) {
            log.error(
                    "Failed to load keywords from directory '{}': {}",
                    keywordsDirectory,
                    e.getMessage(),
                    e);
            // continue with empty weights rather than failing
        }
    }

    /** Finds all JSON files in the keywords directory. */
    private List<String> findKeywordFiles() throws IOException, URISyntaxException {
        List<String> keywordFiles = new ArrayList<>();

        // try to get the resource directory
        ClassLoader classLoader = getClass().getClassLoader();
        URI resourceUri =
                classLoader.getResource(keywordsDirectory) != null
                        ? classLoader.getResource(keywordsDirectory).toURI()
                        : null;

        if (resourceUri == null) {
            log.warn("Keywords directory '{}' not found in classpath", keywordsDirectory);
            return keywordFiles;
        }

        Path keywordsPath;
        FileSystem fileSystem = null;

        try {
            if (resourceUri.getScheme().equals("jar")) {
                fileSystem = FileSystems.newFileSystem(resourceUri, Collections.emptyMap());
                keywordsPath = fileSystem.getPath(keywordsDirectory);
            } else {
                keywordsPath = Paths.get(resourceUri);
            }

            // find all .json files in the directory
            try (Stream<Path> files = Files.list(keywordsPath)) {
                files.filter(path -> path.toString().toLowerCase().endsWith(".json"))
                        .forEach(
                                path -> {
                                    String filename =
                                            keywordsDirectory + "/" + path.getFileName().toString();
                                    keywordFiles.add(filename);
                                });
            }

        } finally {
            if (fileSystem != null) {
                fileSystem.close();
            }
        }

        return keywordFiles;
    }

    /** Loads keywords from a single JSON file. */
    private int loadKeywordFile(String filename) {
        try (InputStream inputStream = getClass().getClassLoader().getResourceAsStream(filename)) {
            if (inputStream == null) {
                log.warn("Keyword file '{}' not found", filename);
                return 0;
            }

            // load the nested JSON structure
            TypeReference<Map<String, Map<String, Double>>> typeRef = new TypeReference<>() {};
            Map<String, Map<String, Double>> keywordCategories =
                    objectMapper.readValue(inputStream, typeRef);

            int keywordsLoaded = 0;
            // flatten all categories into the main keyword weights map
            for (Map.Entry<String, Map<String, Double>> categoryEntry :
                    keywordCategories.entrySet()) {
                String category = categoryEntry.getKey();
                Map<String, Double> categoryKeywords = categoryEntry.getValue();

                for (Map.Entry<String, Double> keywordEntry : categoryKeywords.entrySet()) {
                    String keyword = keywordEntry.getKey();
                    Double weight = keywordEntry.getValue();

                    // check for conflicts
                    if (keywordWeights.containsKey(keyword)) {
                        Double existingWeight = keywordWeights.get(keyword);
                        if (!existingWeight.equals(weight)) {
                            log.warn(
                                    "Keyword '{}' found in multiple files with different weights: {} and {}. Using first value: {}",
                                    keyword,
                                    existingWeight,
                                    weight,
                                    existingWeight);
                        }
                    } else {
                        keywordWeights.put(keyword, weight);
                        keywordsLoaded++;
                    }
                }

                log.debug(
                        "Loaded category '{}' with {} keywords from '{}'",
                        category,
                        categoryKeywords.size(),
                        filename);
            }

            return keywordsLoaded;

        } catch (IOException e) {
            log.error("Failed to load keyword file '{}': {}", filename, e.getMessage(), e);
            return 0;
        }
    }

    /**
     * Calculates the context factor based on keyword weighting in the event context.
     *
     * @param context The event context containing lines before, matched line, and lines after.
     * @return The context factor (1.0 + total keyword weights).
     */
    public double calculateContextFactor(EventContext context) {
        if (context == null) {
            return 1.0; // no context available
        }

        List<String> allLines = getAllContextLines(context);
        if (allLines.isEmpty()) {
            return 1.0; // no lines to analyze
        }

        double totalWeight = 0.0;
        Map<String, Integer> keywordCounts = new HashMap<>();

        for (String line : allLines) {
            for (Map.Entry<String, Double> entry : keywordWeights.entrySet()) {
                String keyword = entry.getKey();
                Double weight = entry.getValue();

                if (line.contains(keyword)) {
                    totalWeight += weight;
                    keywordCounts.merge(keyword, 1, Integer::sum);

                    log.debug(
                            "Found keyword '{}' in line: '{}', adding weight: {}",
                            keyword,
                            line.trim(),
                            weight);
                }
            }
        }

        if (!keywordCounts.isEmpty()) {
            log.debug(
                    "Context analysis summary - Keywords found: {}, Total weight: {}",
                    keywordCounts,
                    totalWeight);
        }

        return 1.0 + totalWeight;
    }

    /**
     * Extracts all context lines from the EventContext.
     *
     * @param context The event context.
     * @return A list of all context lines.
     */
    private List<String> getAllContextLines(EventContext context) {
        List<String> allLines = new ArrayList<>();

        // add lines before the match
        if (context.getLinesBefore() != null) {
            allLines.addAll(context.getLinesBefore());
        }

        // add the matched line itself
        if (context.getMatchedLine() != null) {
            allLines.add(context.getMatchedLine());
        }

        // add lines after the match
        if (context.getLinesAfter() != null) {
            allLines.addAll(context.getLinesAfter());
        }

        return allLines;
    }

    /**
     * Returns the current keyword weights for debugging/inspection purposes.
     *
     * @return A copy of the current keyword weights map.
     */
    public Map<String, Double> getKeywordWeights() {
        return new HashMap<>(keywordWeights);
    }

    /**
     * Reloads all keywords from the keywords directory. Useful for runtime updates without restart.
     */
    public void reloadKeywords() {
        log.info("Reloading keywords from directory '{}'", keywordsDirectory);
        loadKeywords();
    }
}

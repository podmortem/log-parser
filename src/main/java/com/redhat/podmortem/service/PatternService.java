package com.redhat.podmortem.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.redhat.podmortem.common.model.pattern.PatternSet;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Stream;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@ApplicationScoped
public class PatternService {

    private static final Logger log = LoggerFactory.getLogger(PatternService.class);
    private final List<PatternSet> loadedPatternSets = new ArrayList<>();
    private final ObjectMapper yamlMapper = new ObjectMapper(new YAMLFactory());

    @ConfigProperty(name = "pattern.directory")
    String patternDirectoryPath;

    @PostConstruct
    void loadPatterns() {
        log.info("Loading patterns from directory: {}", patternDirectoryPath);
        File dir = new File(patternDirectoryPath);

        if (!dir.exists() || !dir.isDirectory()) {
            log.error(
                    "Pattern directory does not exist or is not a directory: {}",
                    patternDirectoryPath);
            return;
        }

        try (Stream<Path> paths = Files.walk(Paths.get(patternDirectoryPath))) {
            paths.filter(Files::isRegularFile)
                    .filter(
                            path ->
                                    path.toString().endsWith(".yml")
                                            || path.toString().endsWith(".yaml"))
                    .forEach(this::loadPatternFile);
        } catch (IOException e) {
            log.error("Error walking pattern directory: {}", patternDirectoryPath, e);
        }

        log.info("Successfully loaded {} pattern sets.", loadedPatternSets.size());
    }

    /**
     * A helper method to parse a single YAML pattern file into a {@link PatternSet} object and add
     * it to the in-memory list.
     *
     * @param path The path to the pattern file.
     */
    private void loadPatternFile(Path path) {
        log.debug("Attempting to load pattern file: {}", path);
        try {
            PatternSet patternSet = yamlMapper.readValue(path.toFile(), PatternSet.class);
            loadedPatternSets.add(patternSet);
        } catch (IOException e) {
            log.error("Failed to parse pattern file: {}", path, e);
        }
    }

    /**
     * Provides public, read-only access to all loaded pattern sets.
     *
     * @return An unmodifiable list of the loaded {@link PatternSet} objects.
     */
    public List<PatternSet> getPatternSets() {
        return Collections.unmodifiableList(loadedPatternSets);
    }
}

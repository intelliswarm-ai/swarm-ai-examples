/*
 * SwarmAI Framework
 * Copyright (c) 2025 IntelliSwarm.ai (Apache License 2.0)
 */
package ai.intelliswarm.swarmai.config;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.io.FileSystemResource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;

/**
 * Loads a {@code .env} file into the Spring {@link org.springframework.core.env.Environment}
 * early in the boot sequence, searching the current working directory and all parents
 * up to the filesystem root.
 *
 * <p>This is needed because the common case of running an individual example main
 * (e.g. {@code MultiProviderWorkflow.main()}) from an IDE uses the module's subfolder
 * as the working directory — so a repo-root {@code .env} would otherwise be invisible.
 *
 * <p>Registered via {@code META-INF/spring.factories}. Runs at {@link Ordered#HIGHEST_PRECEDENCE + 10}
 * so later property sources (application.yml, command-line args, env vars) can still override
 * individual keys.
 *
 * <p>Values in {@code .env} become both Spring properties AND JVM system properties, so code
 * that reads {@code System.getenv("OPENAI_API_KEY")} also sees them.
 */
public class ParentDirDotenvPostProcessor implements EnvironmentPostProcessor, Ordered {

    /** File name to search for (standard convention). */
    private static final String DOTENV_FILENAME = ".env";

    /** Safety cap on directory traversal (in case the project lives very deep). */
    private static final int MAX_PARENT_DEPTH = 10;

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 10;
    }

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        Path dotenv = findDotenvFile();
        if (dotenv == null) {
            return;  // no .env found — rely on real env vars / application.yml
        }

        try {
            Properties raw = new Properties();
            raw.load(new FileSystemResource(dotenv.toFile()).getInputStream());

            Map<String, Object> props = new LinkedHashMap<>();
            for (String name : raw.stringPropertyNames()) {
                String value = raw.getProperty(name);
                if (value == null) continue;
                // Trim quotes that shell users may wrap values in
                value = stripSurroundingQuotes(value.trim());
                props.put(name, value);
                // Mirror into JVM system properties so code reading System.getProperty sees them.
                // We DO NOT overwrite if already set via -D or real env var.
                if (System.getProperty(name) == null && System.getenv(name) == null) {
                    System.setProperty(name, value);
                }
            }

            // Highest-precedence so values are always available, but placed AFTER command-line
            // property sources so those still win for explicit overrides.
            environment.getPropertySources().addLast(
                    new MapPropertySource("dotenv:" + dotenv.toAbsolutePath(), props));

            // Minimal log — avoid logging the values themselves (keys may be secret-shaped names)
            application.setDefaultProperties(
                    Map.of("swarmai.dotenv.loaded-from", dotenv.toAbsolutePath().toString()));
        } catch (IOException e) {
            // Swallow: if we can't read the file, user will see the normal "API key missing" error.
        }
    }

    /**
     * Walk up from the current working directory until a {@code .env} file is found
     * or we hit the filesystem root / max depth.
     */
    private static Path findDotenvFile() {
        Path cwd = Paths.get("").toAbsolutePath();
        Path dir = cwd;
        for (int depth = 0; depth < MAX_PARENT_DEPTH && dir != null; depth++) {
            Path candidate = dir.resolve(DOTENV_FILENAME);
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
            dir = dir.getParent();
        }
        return null;
    }

    private static String stripSurroundingQuotes(String s) {
        if (s.length() >= 2) {
            char first = s.charAt(0);
            char last = s.charAt(s.length() - 1);
            if ((first == '"' && last == '"') || (first == '\'' && last == '\'')) {
                return s.substring(1, s.length() - 1);
            }
        }
        return s;
    }
}

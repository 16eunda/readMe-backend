package com.ReadMe.demo.config;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.MutablePropertySources;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Loads ./.env into the Spring Environment for local development, regardless of
 * how the app is launched (terminal, Gradle, or an IDE run configuration).
 * Real OS environment variables (used in production/Docker) always take precedence,
 * since this is added as the lowest-priority property source.
 */
public class DotenvEnvironmentPostProcessor implements EnvironmentPostProcessor {

    @Override
    public void postProcessEnvironment(org.springframework.core.env.ConfigurableEnvironment environment,
                                        SpringApplication application) {
        File envFile = new File(System.getProperty("user.dir"), ".env");
        if (!envFile.exists()) {
            return;
        }

        Map<String, Object> dotenv = new LinkedHashMap<>();
        try {
            for (String line : Files.readAllLines(envFile.toPath())) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#") || !line.contains("=")) {
                    continue;
                }
                int idx = line.indexOf('=');
                String key = line.substring(0, idx).trim();
                String value = line.substring(idx + 1).trim().replaceAll("^[\"']|[\"']$", "");
                dotenv.put(key, value);
            }
        } catch (IOException e) {
            return;
        }

        if (dotenv.isEmpty()) {
            return;
        }

        MutablePropertySources sources = environment.getPropertySources();
        sources.addLast(new MapPropertySource("dotenv", dotenv));
    }
}

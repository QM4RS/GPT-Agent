package com.QM4RS.agent.core;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

public class ConfigStore {

    private final Path configPath;

    public ConfigStore() {
        this.configPath = Path.of(System.getProperty("user.home"), ".gpt-agent", "config.properties");
    }

    public Path getConfigPath() {
        return configPath;
    }

    public AppConfig load() {
        AppConfig cfg = new AppConfig();

        // 1) Load from disk (if exists)
        if (Files.exists(configPath)) {
            Properties p = new Properties();
            try (InputStream in = Files.newInputStream(configPath)) {
                p.load(in);
                cfg.setApiKey(p.getProperty("openai.apiKey", ""));
                cfg.setModel(p.getProperty("openai.model", "gpt-4.1"));
            } catch (Exception ignored) {
            }
        }

        // 2) Override by ENV (if provided)
        String envKey = System.getenv("OPENAI_API_KEY");
        if (envKey != null && !envKey.isBlank()) {
            cfg.setApiKey(envKey.trim());
        }

        String envModel = System.getenv("OPENAI_MODEL");
        if (envModel != null && !envModel.isBlank()) {
            cfg.setModel(envModel.trim());
        }

        return cfg;
    }

    public void save(AppConfig cfg) throws IOException {
        Files.createDirectories(configPath.getParent());

        Properties p = new Properties();
        p.setProperty("openai.apiKey", cfg.getApiKey());
        p.setProperty("openai.model", cfg.getModel());

        try (OutputStream out = Files.newOutputStream(configPath)) {
            p.store(out, "GPT-Agent local config");
        }
    }
}

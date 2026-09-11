package net.minedevhd.invalidsessionfix.config;

import net.minecraftforge.common.config.Configuration;

import java.io.File;

public final class ModConfig {
    private final Configuration configuration;

    private boolean autoRepair;
    private boolean autoReconnect;
    private int checkIntervalSeconds;
    private int initialDelaySeconds;

    public ModConfig(File file) {
        configuration = new Configuration(file);
        load();
    }

    public synchronized void load() {
        configuration.load();

        autoRepair = configuration.getBoolean(
            "autoRepair",
            Configuration.CATEGORY_GENERAL,
            true,
            "Automatically validate the current Minecraft access token and repair it when it is no longer accepted."
        );
        autoReconnect = configuration.getBoolean(
            "autoReconnect",
            Configuration.CATEGORY_GENERAL,
            true,
            "Reconnect to the last server after a session was repaired from a disconnect screen."
        );
        checkIntervalSeconds = configuration.getInt(
            "checkIntervalSeconds",
            Configuration.CATEGORY_GENERAL,
            300,
            60,
            3600,
            "How often the session is validated in the background."
        );
        initialDelaySeconds = configuration.getInt(
            "initialDelaySeconds",
            Configuration.CATEGORY_GENERAL,
            10,
            1,
            120,
            "Delay after Minecraft startup before the first validation."
        );

        if (configuration.hasChanged()) {
            configuration.save();
        }
    }

    public synchronized boolean isAutoRepair() {
        return autoRepair;
    }

    public synchronized boolean isAutoReconnect() {
        return autoReconnect;
    }

    public synchronized int getCheckIntervalSeconds() {
        return checkIntervalSeconds;
    }

    public synchronized int getInitialDelaySeconds() {
        return initialDelaySeconds;
    }

    public synchronized void setAutoRepair(boolean value) {
        autoRepair = value;
        configuration.get(Configuration.CATEGORY_GENERAL, "autoRepair", true).set(value);
        configuration.save();
    }

    public synchronized void setAutoReconnect(boolean value) {
        autoReconnect = value;
        configuration.get(Configuration.CATEGORY_GENERAL, "autoReconnect", true).set(value);
        configuration.save();
    }
}

package com.westshield.portal;

import org.bukkit.plugin.java.JavaPlugin;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.concurrent.TimeUnit;

/** Starts the pinned Caddy router with the Minecraft server lifecycle. */
public final class WestShieldPortal extends JavaPlugin {
    private volatile Process router;
    private VoiceAuthGuard voiceGuard;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        voiceGuard = new VoiceAuthGuard(this);
        voiceGuard.register();
        getServer().getPluginManager().registerEvents(new BedrockInvites(this), this);
        try {
            Path root = Path.of("").toAbsolutePath().normalize();
            Path executable = resolve(root, getConfig().getString("binary", "bin/caddy"));
            Path configuration = resolve(root, getConfig().getString("configuration", "Caddyfile"));
            String expected = getConfig().getString("sha256", "");
            if (!expected.matches("[0-9a-f]{64}")) {
                throw new IOException("Configure the expected Caddy SHA-256 digest");
            }
            try (var input = Files.newInputStream(executable)) {
                MessageDigest digest = MessageDigest.getInstance("SHA-256");
                byte[] buffer = new byte[65536];
                for (int count; (count = input.read(buffer)) != -1;) {
                    digest.update(buffer, 0, count);
                }
                if (!HexFormat.of().formatHex(digest.digest()).equals(expected)) {
                    throw new IOException("Caddy checksum does not match the pinned build");
                }
            }
            if (!Files.isRegularFile(configuration)) {
                throw new IOException("Caddy configuration file is missing");
            }
            if (!Files.isExecutable(executable) && !executable.toFile().setExecutable(true, true)) {
                throw new IOException("Unable to mark Caddy executable");
            }
            Path log = getDataFolder().toPath().resolve("router.log");
            router = new ProcessBuilder(executable.toString(), "run", "--config", configuration.toString(), "--adapter", "caddyfile")
                    .directory(root.toFile())
                    .redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.appendTo(log.toFile()))
                    .start();
            getServer().getScheduler().runTaskTimerAsynchronously(this, () -> {
                if (router != null && !router.isAlive()) {
                    getLogger().severe("Web router exited; inspect plugins/WestShieldPortal/router.log. Restart the server after correcting the configuration.");
                    getServer().getScheduler().cancelTasks(this);
                }
            }, 100L, 200L);
            getLogger().info("Started the web router for voice, BlueMap and Plan");
        } catch (Exception failure) {
            getLogger().severe("Web router could not start: " + failure.getMessage());
            getServer().getPluginManager().disablePlugin(this);
        }
    }

    private static Path resolve(Path root, String configured) throws IOException {
        if (configured == null || configured.isBlank()) throw new IOException("Empty web router path");
        Path resolved = root.resolve(configured).normalize();
        if (!resolved.startsWith(root)) throw new IOException("Web router paths must stay inside the server directory");
        return resolved;
    }

    @Override
    public void onDisable() {
        if (voiceGuard != null) {
            voiceGuard.close();
            voiceGuard = null;
        }
        if (router == null) return;
        router.destroy();
        try {
            if (!router.waitFor(5, TimeUnit.SECONDS)) router.destroyForcibly();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            router.destroyForcibly();
        }
        router = null;
    }
}

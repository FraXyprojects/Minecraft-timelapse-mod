package com.fraxyprojects.timelapse;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.reflect.TypeToken;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.level.BlockEvent;
import net.minecraftforge.event.server.ServerLifecycleHooks;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.server.command.ConfigCommand;
import net.minecraftforge.fml.event.lifecycle.FMLServerStartingEvent;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.Type;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Mod(FraxyTimelapseMod.MOD_ID)
public final class FraxyTimelapseMod {
    public static final String MOD_ID = "fraxy_timelapse";
    private static final Logger LOGGER = LogManager.getLogger(MOD_ID);
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private static TimelapseConfig config;
    private static final Map<String, Integer> pendingChanges = new ConcurrentHashMap<>();
    private static final Map<String, Long> lastTriggerMs = new ConcurrentHashMap<>();
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    public FraxyTimelapseMod() {
        MinecraftForge.EVENT_BUS.register(this);
    }

    @SubscribeEvent
    public void onServerStarted(ServerStartedEvent event) {
        MinecraftServer server = event.getServer();
        Path configPath = server.getServerDirectory().toPath().resolve("config/fraxy-timelapse.json");
        config = loadConfig(configPath);
        saveConfig(configPath, config);
        LOGGER.info("FraXy Timelapse loaded: {} camera zones, threshold={}, cooldown={}s",
                config.cameras.size(), config.changeThreshold, config.cooldownSeconds);
    }

    @SubscribeEvent
    public void onServerStarting(FMLServerStartingEvent event) {
        ConfigCommand.register(event.getServer().getCommands().getDispatcher(), event.getServer().getCommandSource().getServer());
    }

    @SubscribeEvent
    public void onBlockEvent(BlockEvent event) {
        if (config == null || event.getLevel().isClientSide()) {
            return;
        }

        if (!(event.getLevel() instanceof ServerLevel level)) {
            return;
        }

        BlockPos pos = event.getPos();
        for (CameraZone camera : config.cameras.values()) {
            if (!camera.enabled || !camera.world.equals(level.dimension().location().toString())) {
                continue;
            }
            if (!camera.contains(pos)) {
                continue;
            }

            int changes = pendingChanges.merge(camera.id, 1, Integer::sum);
            if (changes < config.changeThreshold) {
                continue;
            }

            long now = System.currentTimeMillis();
            long cooldownMs = config.cooldownSeconds * 1000L;
            long last = lastTriggerMs.getOrDefault(camera.id, 0L);
            if (now - last < cooldownMs) {
                continue;
            }

            pendingChanges.put(camera.id, 0);
            lastTriggerMs.put(camera.id, now);
            triggerWebhook(camera, changes);
        }
    }

    private static void triggerWebhook(CameraZone camera, int changes) {
        if (config.webhookUrl == null || config.webhookUrl.isBlank() || config.webhookToken == null || config.webhookToken.isBlank()) {
            LOGGER.warn("Camera {} reached threshold, but webhook is not configured.", camera.id);
            return;
        }

        JsonObject payload = new JsonObject();
        payload.addProperty("camera", camera.id);
        payload.addProperty("changes", changes);
        payload.addProperty("timestamp", System.currentTimeMillis());

        HttpRequest request = HttpRequest.newBuilder(URI.create(config.webhookUrl))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + config.webhookToken)
                .POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(payload)))
                .build();

        HTTP.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .whenComplete((response, error) -> {
                    if (error != null) {
                        LOGGER.warn("Webhook failed for camera {}: {}", camera.id, error.toString());
                        return;
                    }
                    if (response.statusCode() < 200 || response.statusCode() >= 300) {
                        LOGGER.warn("Webhook returned HTTP {} for camera {}: {}", response.statusCode(), camera.id, response.body());
                        return;
                    }
                    LOGGER.info("Timelapse trigger sent for camera {} after {} block changes.", camera.id, changes);
                });
    }

    private static TimelapseConfig loadConfig(Path path) {
        try {
            Files.createDirectories(path.getParent());
            if (!Files.exists(path)) {
                return TimelapseConfig.defaults();
            }
            try (Reader reader = Files.newBufferedReader(path)) {
                Type type = new TypeToken<TimelapseConfig>() {}.getType();
                TimelapseConfig loaded = GSON.fromJson(reader, type);
                if (loaded == null) {
                    return TimelapseConfig.defaults();
                }
                if (loaded.cameras == null) {
                    loaded.cameras = new LinkedHashMap<>();
                }
                return loaded;
            }
        } catch (IOException | RuntimeException e) {
            LOGGER.error("Failed to load FraXy Timelapse config; using defaults.", e);
            return TimelapseConfig.defaults();
        }
    }

    private static void saveConfig(Path path, TimelapseConfig config) {
        try {
            Files.createDirectories(path.getParent());
            try (Writer writer = Files.newBufferedWriter(path)) {
                GSON.toJson(config, writer);
            }
        } catch (IOException e) {
            LOGGER.error("Failed to save FraXy Timelapse config.", e);
        }
    }

    private static final class TimelapseConfig {
        String webhookUrl = "";
        String webhookToken = "";
        int changeThreshold = 5;
        int cooldownSeconds = 600;
        Map<String, CameraZone> cameras = new LinkedHashMap<>();

        static TimelapseConfig defaults() {
            TimelapseConfig config = new TimelapseConfig();
            config.cameras.put("camera1", CameraZone.defaultZone("camera1"));
            config.cameras.put("camera2", CameraZone.defaultZone("camera2"));
            config.cameras.put("camera3", CameraZone.defaultZone("camera3"));
            config.cameras.put("camera4", CameraZone.defaultZone("camera4"));
            return config;
        }
    }

    private static final class CameraZone {
        String id;
        boolean enabled = false;
        String world = "minecraft:overworld";
        int minX = 0;
        int minY = -64;
        int minZ = 0;
        int maxX = 0;
        int maxY = 320;
        int maxZ = 0;

        static CameraZone defaultZone(String id) {
            CameraZone zone = new CameraZone();
            zone.id = id;
            zone.enabled = "camera1".equals(id);
            zone.minX = -100;
            zone.maxX = 100;
            zone.minZ = -100;
            zone.maxZ = 100;
            return zone;
        }

        boolean contains(BlockPos pos) {
            return pos.getX() >= minX && pos.getX() <= maxX
                    && pos.getY() >= minY && pos.getY() <= maxY
                    && pos.getZ() >= minZ && pos.getZ() <= maxZ;
        }
    }
}

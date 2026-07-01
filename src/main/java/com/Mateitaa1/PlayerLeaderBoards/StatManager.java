package com.Mateitaa1.PlayerLeaderBoards;

import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;

public class StatManager {

    private final PlayerLeaderBoards plugin;
    private final StatType type;
    private final File file;
    private final Map<UUID, Double> values = new HashMap<>();
    private final Map<UUID, String> names = new HashMap<>();
    private boolean dirty = false;

    public StatManager(PlayerLeaderBoards plugin, StatType type) {
        this.plugin = plugin;
        this.type = type;
        this.file = new File(plugin.getDataFolder(), type.getFileName());
        load();
    }

    public void load() {
        values.clear();
        names.clear();
        if (!file.exists()) {
            return;
        }
        YamlConfiguration cfg = YamlConfiguration.loadConfiguration(file);
        for (String key : cfg.getKeys(false)) {
            try {
                UUID uuid = UUID.fromString(key);
                double value = cfg.getDouble(key + ".value", 0);
                String name = cfg.getString(key + ".name", "???");
                values.put(uuid, value);
                names.put(uuid, name);
            } catch (IllegalArgumentException ignored) {
                // clave invalida, se ignora
            }
        }
    }

    public void save() {
        YamlConfiguration cfg = new YamlConfiguration();
        for (Map.Entry<UUID, Double> entry : values.entrySet()) {
            String key = entry.getKey().toString();
            cfg.set(key + ".value", entry.getValue());
            cfg.set(key + ".name", names.getOrDefault(entry.getKey(), "???"));
        }
        try {
            cfg.save(file);
            dirty = false;
        } catch (IOException e) {
            plugin.getLogger().log(Level.WARNING, "No se pudo guardar " + type.getFileName(), e);
        }
    }

    public void add(Player player, double amount) {
        UUID uuid = player.getUniqueId();
        values.merge(uuid, amount, Double::sum);
        names.put(uuid, player.getName());
        dirty = true;
    }

    public double get(UUID uuid) {
        return values.getOrDefault(uuid, 0.0);
    }

    public String getName(UUID uuid) {
        return names.getOrDefault(uuid, "???");
    }

    public boolean isDirty() {
        return dirty;
    }

    public List<Map.Entry<UUID, Double>> getTop(int count) {
        List<Map.Entry<UUID, Double>> list = new ArrayList<>(values.entrySet());
        list.sort((a, b) -> Double.compare(b.getValue(), a.getValue()));
        if (count >= 0 && list.size() > count) {
            return list.subList(0, count);
        }
        return list;
    }

    public StatType getType() {
        return type;
    }
}

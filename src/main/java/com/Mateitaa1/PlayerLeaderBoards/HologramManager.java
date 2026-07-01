package com.Mateitaa1.PlayerLeaderBoards;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataType;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;

public class HologramManager {

    private final PlayerLeaderBoards plugin;
    private final File file;
    private final NamespacedKey idKey;
    private final NamespacedKey lineKey;
    private final List<HologramEntry> entries = new ArrayList<>();

    public HologramManager(PlayerLeaderBoards plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "hologram_locations.yml");
        this.idKey = new NamespacedKey(plugin, "plb_holo_id");
        this.lineKey = new NamespacedKey(plugin, "plb_holo_line");
    }

    private static class HologramEntry {
        final UUID id;
        final StatType type;
        final String world;
        final double x, y, z;
        final int lines;

        HologramEntry(UUID id, StatType type, String world, double x, double y, double z, int lines) {
            this.id = id;
            this.type = type;
            this.world = world;
            this.x = x;
            this.y = y;
            this.z = z;
            this.lines = lines;
        }
    }

    public void load() {
        entries.clear();
        if (!file.exists()) {
            return;
        }
        YamlConfiguration cfg = YamlConfiguration.loadConfiguration(file);
        for (String key : cfg.getKeys(false)) {
            try {
                UUID id = UUID.fromString(key);
                StatType type = StatType.fromId(cfg.getString(key + ".type"));
                if (type == null) {
                    continue;
                }
                String world = cfg.getString(key + ".world");
                double x = cfg.getDouble(key + ".x");
                double y = cfg.getDouble(key + ".y");
                double z = cfg.getDouble(key + ".z");
                int lines = cfg.getInt(key + ".lines", plugin.getTopCount() + 1);
                entries.add(new HologramEntry(id, type, world, x, y, z, lines));
            } catch (Exception e) {
                plugin.getLogger().warning("Entrada invalida en hologram_locations.yml: " + key);
            }
        }
    }

    public void save() {
        YamlConfiguration cfg = new YamlConfiguration();
        for (HologramEntry e : entries) {
            String key = e.id.toString();
            cfg.set(key + ".type", e.type.getId());
            cfg.set(key + ".world", e.world);
            cfg.set(key + ".x", e.x);
            cfg.set(key + ".y", e.y);
            cfg.set(key + ".z", e.z);
            cfg.set(key + ".lines", e.lines);
        }
        try {
            cfg.save(file);
        } catch (IOException ex) {
            plugin.getLogger().log(Level.WARNING, "No se pudo guardar hologram_locations.yml", ex);
        }
    }

    /** Fuerza la carga del chunk de cada hologram guardado y repara los que falten (ej: tras /kill @e). */
    public void recreateAll() {
        for (HologramEntry e : entries) {
            World world = Bukkit.getWorld(e.world);
            if (world == null) {
                continue;
            }
            Chunk chunk = world.getChunkAt((int) e.x >> 4, (int) e.z >> 4);
            chunk.setForceLoaded(true);
            List<ArmorStand> stands = findStands(world, e);
            if (stands.size() < e.lines) {
                removeStands(world, e);
                spawnStands(world, e);
            }
        }
        updateAll();
    }

    private List<ArmorStand> findStands(World world, HologramEntry e) {
        List<ArmorStand> found = new ArrayList<>();
        Location loc = new Location(world, e.x, e.y, e.z);
        for (Entity ent : world.getNearbyEntities(loc, 2, e.lines + 2, 2)) {
            if (!(ent instanceof ArmorStand)) {
                continue;
            }
            ArmorStand stand = (ArmorStand) ent;
            String tagId = stand.getPersistentDataContainer().get(idKey, PersistentDataType.STRING);
            if (e.id.toString().equals(tagId)) {
                found.add(stand);
            }
        }
        found.sort(Comparator.comparingInt(s -> {
            Integer line = s.getPersistentDataContainer().get(lineKey, PersistentDataType.INTEGER);
            return line == null ? 0 : line;
        }));
        return found;
    }

    private void removeStands(World world, HologramEntry e) {
        for (ArmorStand stand : findStands(world, e)) {
            stand.remove();
        }
    }

    private void spawnStands(World world, HologramEntry e) {
        for (int i = 0; i < e.lines; i++) {
            Location loc = new Location(world, e.x + 0.5, e.y + (e.lines - 1 - i) * 0.25, e.z + 0.5);
            world.spawn(loc, ArmorStand.class, as -> {
                as.setVisible(false);
                as.setGravity(false);
                as.setMarker(true);
                as.setSmall(true);
                as.setInvulnerable(true);
                as.setCustomNameVisible(true);
                as.setPersistent(true);
                as.setCustomName(" ");
                as.getPersistentDataContainer().set(idKey, PersistentDataType.STRING, e.id.toString());
                as.getPersistentDataContainer().set(lineKey, PersistentDataType.INTEGER, i);
            });
        }
    }

    public void spawnHologram(Player player, StatType type) {
        World world = player.getWorld();
        Location base = player.getLocation();
        int lines = plugin.getTopCount() + 1;
        HologramEntry e = new HologramEntry(UUID.randomUUID(), type, world.getName(),
                base.getX(), base.getY(), base.getZ(), lines);
        entries.add(e);
        world.getChunkAt(base).setForceLoaded(true);
        spawnStands(world, e);
        save();
        updateEntry(e);
    }

    public boolean removeNearest(Player player) {
        Location loc = player.getLocation();
        HologramEntry closest = null;
        double closestDistSq = 10 * 10;
        for (HologramEntry e : entries) {
            if (loc.getWorld() == null || !e.world.equals(loc.getWorld().getName())) {
                continue;
            }
            double dx = e.x - loc.getX();
            double dy = e.y - loc.getY();
            double dz = e.z - loc.getZ();
            double distSq = dx * dx + dy * dy + dz * dz;
            if (distSq < closestDistSq) {
                closestDistSq = distSq;
                closest = e;
            }
        }
        if (closest == null) {
            return false;
        }
        World world = Bukkit.getWorld(closest.world);
        if (world != null) {
            removeStands(world, closest);
            world.getChunkAt((int) closest.x >> 4, (int) closest.z >> 4).setForceLoaded(false);
        }
        entries.remove(closest);
        save();
        return true;
    }

    public void updateAll() {
        for (HologramEntry e : entries) {
            updateEntry(e);
        }
    }

    public void updateStat(StatType type) {
        for (HologramEntry e : entries) {
            if (e.type == type) {
                updateEntry(e);
            }
        }
    }

    private void updateEntry(HologramEntry e) {
        World world = Bukkit.getWorld(e.world);
        if (world == null) {
            return;
        }
        List<ArmorStand> stands = findStands(world, e);
        if (stands.isEmpty()) {
            return;
        }
        StatManager manager = plugin.getStatManager(e.type);
        List<String> lines = buildLines(manager, e.type, e.lines);
        for (int i = 0; i < stands.size() && i < lines.size(); i++) {
            stands.get(i).setCustomName(ChatColor.translateAlternateColorCodes('&', lines.get(i)));
        }
    }

    private List<String> buildLines(StatManager manager, StatType type, int lineCount) {
        List<String> lines = new ArrayList<>();
        lines.add(type.getTitle());
        List<Map.Entry<UUID, Double>> top = manager.getTop(lineCount - 1);
        String[] medals = {"&6#1", "&7#2", "&c#3"};
        for (int i = 0; i < lineCount - 1; i++) {
            if (i < top.size()) {
                Map.Entry<UUID, Double> entry = top.get(i);
                String prefix = i < medals.length ? medals[i] : "&f#" + (i + 1);
                lines.add(prefix + " &f" + manager.getName(entry.getKey()) + " &8- &a" + type.formatValue(entry.getValue()));
            } else {
                lines.add("&8#" + (i + 1) + " &7---");
            }
        }
        return lines;
    }

    public int getHologramCount() {
        return entries.size();
    }
}

package com.Mateitaa1.PlayerLeaderBoards;

import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;

/**
 * PlayerLeaderBoards - extended version
 *
 * Tracks 5 stats: kills, playtime, messages, blocksmined, distancewalked.
 * Each stat has its own YAML storage file, its own hologram-spawn command,
 * and shows up in /leaderboard <stat>.
 *
 * The hologram approach uses stacked, invisible, marker ArmorStands with
 * custom names - same technique the original plugin used (hence the
 * /cleanuporphanedholograms command to sweep up stray armor stands).
 */
public class PlayerLeaderBoards extends JavaPlugin implements Listener, CommandExecutor {

    // stat key -> (player UUID -> value)
    private final Map<String, Map<UUID, Double>> stats = new HashMap<>();

    // stat key -> yml file name (loaded from config storage.files)
    private final Map<String, String> storageFiles = new HashMap<>();

    // player UUID -> name cache, so leaderboards can show names of offline players too
    private final Map<UUID, String> nameCache = new HashMap<>();

    // player UUID -> join timestamp (millis), for playtime tracking
    private final Map<UUID, Long> sessionStart = new HashMap<>();

    // player UUID -> last location, for distance walked tracking
    private final Map<UUID, Location> lastLocation = new HashMap<>();

    // tracked spawned holograms, so /removeholograms and cleanup can find them
    private final List<ArmorStand> activeHolograms = new ArrayList<>();

    private static final List<String> STAT_KEYS = Arrays.asList(
            "kills", "playtime", "messages", "blocksmined", "distancewalked"
    );

    @Override
    public void onEnable() {
        saveDefaultConfig();
        reloadConfig();

        for (String key : STAT_KEYS) {
            storageFiles.put(key, getConfig().getString("storage.files." + key, key + ".yml"));
            stats.put(key, new HashMap<>());
            loadStat(key);
        }

        getServer().getPluginManager().registerEvents(this, this);

        for (String cmd : Arrays.asList(
                "spawnkillshologram", "spawnplaytimehologram", "spawnmessageshologram",
                "spawnblocksminedhologram", "spawndistancewalkedhologram",
                "removeholograms", "cleanuporphanedholograms", "leaderboard")) {
            if (getCommand(cmd) != null) {
                getCommand(cmd).setExecutor(this);
            }
        }

        // Playtime ticker: adds 1 minute to every online player's playtime each minute
        new BukkitRunnable() {
            @Override
            public void run() {
                for (Player p : getServer().getOnlinePlayers()) {
                    addStat("playtime", p.getUniqueId(), 1.0);
                    nameCache.put(p.getUniqueId(), p.getName());
                }
            }
        }.runTaskTimer(this, 20L * 60, 20L * 60);

        // Auto-save loop, interval comes from config
        long saveIntervalTicks = 20L * 60 * Math.max(1, getConfig().getInt("general.auto-save-interval", 5));
        new BukkitRunnable() {
            @Override
            public void run() {
                saveAllStats();
            }
        }.runTaskTimer(this, saveIntervalTicks, saveIntervalTicks);

        getLogger().info("PlayerLeaderBoards enabled - tracking: " + String.join(", ", STAT_KEYS));
    }

    @Override
    public void onDisable() {
        saveAllStats();
        removeAllHolograms();
    }

    // ---------------------------------------------------------------
    // Event listeners
    // ---------------------------------------------------------------

    @EventHandler
    public void onEntityDeath(EntityDeathEvent event) {
        if (!getConfig().getBoolean("tracking.track-kills", true)) return;
        Player killer = event.getEntity().getKiller();
        if (killer != null) {
            addStat("kills", killer.getUniqueId(), 1.0);
            nameCache.put(killer.getUniqueId(), killer.getName());
        }
    }

    @EventHandler
    public void onChat(AsyncPlayerChatEvent event) {
        if (!getConfig().getBoolean("tracking.track-messages", true)) return;
        Player p = event.getPlayer();
        addStat("messages", p.getUniqueId(), 1.0);
        nameCache.put(p.getUniqueId(), p.getName());
    }

    @EventHandler
    public void onBlockBreak(BlockBreakEvent event) {
        if (!getConfig().getBoolean("tracking.track-blocksmined", true)) return;
        Player p = event.getPlayer();
        addStat("blocksmined", p.getUniqueId(), 1.0);
        nameCache.put(p.getUniqueId(), p.getName());
    }

    @EventHandler
    public void onMove(PlayerMoveEvent event) {
        if (!getConfig().getBoolean("tracking.track-distancewalked", true)) return;
        if (event.getTo() == null) return;

        Player p = event.getPlayer();
        UUID uuid = p.getUniqueId();
        Location from = lastLocation.get(uuid);
        Location to = event.getTo();

        if (from != null && from.getWorld() != null && from.getWorld().equals(to.getWorld())) {
            double distance = from.distance(to);
            // Ignore teleports/huge jumps (e.g. > 10 blocks in one tick) so /tp and portals
            // don't inflate the stat
            if (distance > 0.01 && distance < 10.0) {
                addStat("distancewalked", uuid, distance);
            }
        }
        lastLocation.put(uuid, to);
        nameCache.put(uuid, p.getName());
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player p = event.getPlayer();
        sessionStart.put(p.getUniqueId(), System.currentTimeMillis());
        lastLocation.put(p.getUniqueId(), p.getLocation());
        nameCache.put(p.getUniqueId(), p.getName());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        sessionStart.remove(uuid);
        lastLocation.remove(uuid);
    }

    // ---------------------------------------------------------------
    // Commands
    // ---------------------------------------------------------------

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String name = command.getName().toLowerCase();

        switch (name) {
            case "spawnkillshologram":
                return spawnHologramCommand(sender, "kills");
            case "spawnplaytimehologram":
                return spawnHologramCommand(sender, "playtime");
            case "spawnmessageshologram":
                return spawnHologramCommand(sender, "messages");
            case "spawnblocksminedhologram":
                return spawnHologramCommand(sender, "blocksmined");
            case "spawndistancewalkedhologram":
                return spawnHologramCommand(sender, "distancewalked");
            case "removeholograms":
                removeAllHolograms();
                sender.sendMessage(color("&aTodos los hologramas fueron removidos."));
                return true;
            case "cleanuporphanedholograms":
                return cleanupOrphanedHolograms(sender);
            case "leaderboard":
                return showChatLeaderboard(sender, args);
        }
        return false;
    }

    private boolean spawnHologramCommand(CommandSender sender, String statKey) {
        if (!(sender instanceof Player)) {
            sender.sendMessage("Este comando solo se puede usar en el juego.");
            return true;
        }
        Player player = (Player) sender;
        spawnHologram(statKey, player.getLocation());
        sender.sendMessage(color("&aHologram de &e" + statKey + " &acreado."));
        return true;
    }

    private boolean showChatLeaderboard(CommandSender sender, String[] args) {
        if (args.length < 1 || !STAT_KEYS.contains(args[0].toLowerCase())) {
            sender.sendMessage(color("&cUso: /leaderboard <" + String.join("|", STAT_KEYS) + ">"));
            return true;
        }
        String statKey = args[0].toLowerCase();
        List<Map.Entry<UUID, Double>> top = topEntries(statKey);

        sender.sendMessage(color(getConfig().getString("holograms.titles." + statKey, "&6Top " + statKey)));
        int rank = 1;
        for (Map.Entry<UUID, Double> entry : top) {
            String line = formatLine(statKey, rank, entry.getKey(), entry.getValue());
            sender.sendMessage(color(line));
            rank++;
        }
        return true;
    }

    // ---------------------------------------------------------------
    // Hologram helpers (stacked invisible marker ArmorStands)
    // ---------------------------------------------------------------

    private void spawnHologram(String statKey, Location baseLocation) {
        double spawnHeight = getConfig().getDouble("holograms.spawn-height", 3.0);
        double lineSpacing = getConfig().getDouble("holograms.line-spacing", 0.3);

        Location current = baseLocation.clone().add(0, spawnHeight, 0);

        // Title line
        String title = getConfig().getString("holograms.titles." + statKey, "&6Top " + statKey);
        activeHolograms.add(spawnHologramLine(current, color(title)));
        current = current.clone().subtract(0, lineSpacing, 0);

        // Stat lines
        List<Map.Entry<UUID, Double>> top = topEntries(statKey);
        int rank = 1;
        for (Map.Entry<UUID, Double> entry : top) {
            String line = formatLine(statKey, rank, entry.getKey(), entry.getValue());
            activeHolograms.add(spawnHologramLine(current, color(line)));
            current = current.clone().subtract(0, lineSpacing, 0);
            rank++;
        }
    }

    private ArmorStand spawnHologramLine(Location loc, String text) {
        ArmorStand stand = (ArmorStand) loc.getWorld().spawnEntity(loc, EntityType.ARMOR_STAND);
        stand.setVisible(false);
        stand.setGravity(false);
        stand.setMarker(true);
        stand.setCustomNameVisible(true);
        stand.setCustomName(text);
        stand.setInvulnerable(true);
        stand.setSmall(true);
        stand.setPersistent(true);
        return stand;
    }

    private void removeAllHolograms() {
        for (ArmorStand stand : activeHolograms) {
            if (stand != null && !stand.isDead()) {
                stand.remove();
            }
        }
        activeHolograms.clear();
    }

    private boolean cleanupOrphanedHolograms(CommandSender sender) {
        if (!(sender instanceof Player)) {
            sender.sendMessage("Este comando solo se puede usar en el juego.");
            return true;
        }
        Player player = (Player) sender;
        int removed = 0;
        for (org.bukkit.entity.Entity entity : player.getNearbyEntities(100, 100, 100)) {
            if (entity instanceof ArmorStand) {
                ArmorStand stand = (ArmorStand) entity;
                if (stand.isMarker() && !stand.isVisible() && stand.getCustomName() != null) {
                    stand.remove();
                    removed++;
                }
            }
        }
        sender.sendMessage(color("&aSe removieron &e" + removed + " &ahologramas huérfanos."));
        return true;
    }

    // ---------------------------------------------------------------
    // Stat storage helpers
    // ---------------------------------------------------------------

    private void addStat(String statKey, UUID uuid, double amount) {
        stats.get(statKey).merge(uuid, amount, Double::sum);
    }

    private List<Map.Entry<UUID, Double>> topEntries(String statKey) {
        int max = getConfig().getInt("general.max-leaderboard-entries", 10);
        return stats.get(statKey).entrySet().stream()
                .sorted((a, b) -> Double.compare(b.getValue(), a.getValue()))
                .limit(max)
                .collect(Collectors.toList());
    }

    private String formatLine(String statKey, int rank, UUID uuid, double value) {
        String format = getConfig().getString("holograms.formats." + statKey, "&e#{rank} &f{player} &7- {value}");
        String playerName = nameCache.getOrDefault(uuid, getServer().getOfflinePlayer(uuid).getName());
        if (playerName == null) playerName = "???";

        String displayValue;
        if (statKey.equals("playtime")) {
            displayValue = formatMinutes(value);
        } else if (statKey.equals("distancewalked")) {
            displayValue = String.format("%.0f bloques", value);
        } else {
            displayValue = String.valueOf((long) value);
        }

        return format.replace("{rank}", String.valueOf(rank))
                .replace("{player}", playerName)
                .replace("{value}", displayValue);
    }

    private String formatMinutes(double minutes) {
        long totalMinutes = (long) minutes;
        long hours = totalMinutes / 60;
        long mins = totalMinutes % 60;
        return hours + "h " + mins + "m";
    }

    private void loadStat(String statKey) {
        File file = new File(getDataFolder(), storageFiles.get(statKey));
        if (!file.exists()) return;
        FileConfiguration data = YamlConfiguration.loadConfiguration(file);
        Map<UUID, Double> map = stats.get(statKey);
        for (String key : data.getKeys(false)) {
            try {
                UUID uuid = UUID.fromString(key);
                map.put(uuid, data.getDouble(key + ".value"));
                String name = data.getString(key + ".name");
                if (name != null) nameCache.put(uuid, name);
            } catch (IllegalArgumentException ignored) {
                // skip malformed entries
            }
        }
    }

    private void saveStat(String statKey) {
        File file = new File(getDataFolder(), storageFiles.get(statKey));
        FileConfiguration data = new YamlConfiguration();
        for (Map.Entry<UUID, Double> entry : stats.get(statKey).entrySet()) {
            String key = entry.getKey().toString();
            data.set(key + ".value", entry.getValue());
            data.set(key + ".name", nameCache.getOrDefault(entry.getKey(), "???"));
        }
        try {
            data.save(file);
        } catch (IOException e) {
            getLogger().warning("No se pudo guardar " + storageFiles.get(statKey) + ": " + e.getMessage());
        }
    }

    private void saveAllStats() {
        for (String key : STAT_KEYS) {
            saveStat(key);
        }
    }

    private String color(String s) {
        return ChatColor.translateAlternateColorCodes('&', s);
    }
}

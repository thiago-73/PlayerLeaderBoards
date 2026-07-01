package com.Mateitaa1.PlayerLeaderBoards;

import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class PlayerLeaderBoards extends JavaPlugin implements Listener, CommandExecutor {

    private final Map<StatType, StatManager> statManagers = new EnumMap<>(StatType.class);
    private HologramManager hologramManager;
    private int topCount;
    private int autoRefreshMinutes;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        if (!getDataFolder().exists()) {
            getDataFolder().mkdirs();
        }

        topCount = getConfig().getInt("top-count", 5);
        autoRefreshMinutes = Math.max(1, getConfig().getInt("auto-refresh-minutes", 5));

        for (StatType type : StatType.values()) {
            statManagers.put(type, new StatManager(this, type));
        }

        hologramManager = new HologramManager(this);
        hologramManager.load();

        getServer().getPluginManager().registerEvents(this, this);

        for (String cmd : Arrays.asList("spawnkillshologram", "spawnplaytimehologram",
                "spawnmessageshologram", "spawnblocksminedhologram", "spawndistancewalkedhologram",
                "leaderboard", "removeholograms")) {
            if (getCommand(cmd) != null) {
                getCommand(cmd).setExecutor(this);
            }
        }

        // Recrea/repara los hologramas guardados un tick despues del arranque
        getServer().getScheduler().runTaskLater(this, hologramManager::recreateAll, 20L);

        // Guardado periodico a disco + refresco completo de todos los hologramas (red de seguridad)
        long refreshTicks = autoRefreshMinutes * 60L * 20L;
        getServer().getScheduler().runTaskTimer(this, () -> {
            saveAllStats();
            hologramManager.updateAll();
        }, refreshTicks, refreshTicks);

        // Contador de tiempo jugado, +1 minuto por jugador online cada minuto real
        getServer().getScheduler().runTaskTimer(this, () -> {
            StatManager manager = statManagers.get(StatType.PLAYTIME);
            for (Player p : getServer().getOnlinePlayers()) {
                manager.add(p, 1);
            }
            if (!getServer().getOnlinePlayers().isEmpty()) {
                hologramManager.updateStat(StatType.PLAYTIME);
            }
        }, 20L * 60L, 20L * 60L);

        getLogger().info("PlayerLeaderBoards habilitado con " + hologramManager.getHologramCount() + " hologramas guardados.");
    }

    @Override
    public void onDisable() {
        saveAllStats();
        if (hologramManager != null) {
            hologramManager.save();
        }
    }

    private void saveAllStats() {
        for (StatManager manager : statManagers.values()) {
            if (manager.isDirty()) {
                manager.save();
            }
        }
    }

    public StatManager getStatManager(StatType type) {
        return statManagers.get(type);
    }

    public int getTopCount() {
        return topCount;
    }

    // ---------- Eventos ----------

    @EventHandler(ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        statManagers.get(StatType.BLOCKSMINED).add(event.getPlayer(), 1);
        hologramManager.updateStat(StatType.BLOCKSMINED);
    }

    @EventHandler
    public void onEntityDeath(EntityDeathEvent event) {
        Player killer = event.getEntity().getKiller();
        if (killer != null) {
            statManagers.get(StatType.KILLS).add(killer, 1);
            hologramManager.updateStat(StatType.KILLS);
        }
    }

    @EventHandler
    public void onChat(AsyncPlayerChatEvent event) {
        Player player = event.getPlayer();
        // Se agenda en el hilo principal porque este evento corre de forma asincronica
        getServer().getScheduler().runTask(this, () -> {
            statManagers.get(StatType.MESSAGES).add(player, 1);
            hologramManager.updateStat(StatType.MESSAGES);
        });
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        Location from = event.getFrom();
        Location to = event.getTo();
        if (to == null || from.getWorld() == null || to.getWorld() == null) {
            return;
        }
        if (!from.getWorld().equals(to.getWorld())) {
            return;
        }
        double distance = from.distance(to);
        // Ignora teletransportes/saltos bruscos (portales, /tp, elytra glitch, etc.)
        if (distance > 10 || distance < 0.01) {
            return;
        }
        statManagers.get(StatType.DISTANCEWALKED).add(event.getPlayer(), distance);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        // No-op por ahora, reservado para futuras limpiezas por jugador
    }

    // ---------- Comandos ----------

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String name = label.toLowerCase();

        if (!(sender instanceof Player) && !name.equals("leaderboard")) {
            sender.sendMessage("Este comando solo se puede usar en el juego.");
            return true;
        }

        switch (name) {
            case "spawnkillshologram":
                return spawnCommand((Player) sender, StatType.KILLS);
            case "spawnplaytimehologram":
                return spawnCommand((Player) sender, StatType.PLAYTIME);
            case "spawnmessageshologram":
                return spawnCommand((Player) sender, StatType.MESSAGES);
            case "spawnblocksminedhologram":
                return spawnCommand((Player) sender, StatType.BLOCKSMINED);
            case "spawndistancewalkedhologram":
                return spawnCommand((Player) sender, StatType.DISTANCEWALKED);
            case "removeholograms":
                Player p = (Player) sender;
                if (!p.hasPermission("playerleaderboards.admin")) {
                    p.sendMessage(ChatColor.RED + "No tenes permiso.");
                    return true;
                }
                if (hologramManager.removeNearest(p)) {
                    p.sendMessage(ChatColor.GREEN + "Hologram eliminado.");
                } else {
                    p.sendMessage(ChatColor.RED + "No hay ningun hologram cerca (10 bloques).");
                }
                return true;
            case "leaderboard":
                return leaderboardCommand(sender, args);
            default:
                return false;
        }
    }

    private boolean spawnCommand(Player player, StatType type) {
        if (!player.hasPermission("playerleaderboards.admin")) {
            player.sendMessage(ChatColor.RED + "No tenes permiso.");
            return true;
        }
        hologramManager.spawnHologram(player, type);
        player.sendMessage(ChatColor.GREEN + "Hologram de " + type.getDisplayName() + " creado en tu posicion.");
        return true;
    }

    private boolean leaderboardCommand(CommandSender sender, String[] args) {
        if (args.length < 1) {
            sender.sendMessage(ChatColor.YELLOW + "Uso: /leaderboard <kills|playtime|messages|blocksmined|distancewalked>");
            return true;
        }
        StatType type = StatType.fromId(args[0]);
        if (type == null) {
            sender.sendMessage(ChatColor.RED + "Estadistica desconocida: " + args[0]);
            return true;
        }
        StatManager manager = statManagers.get(type);
        sender.sendMessage(ChatColor.translateAlternateColorCodes('&', type.getTitle()));
        List<Map.Entry<UUID, Double>> top = manager.getTop(topCount);
        if (top.isEmpty()) {
            sender.sendMessage(ChatColor.GRAY + "Todavia no hay datos.");
            return true;
        }
        int pos = 1;
        for (Map.Entry<UUID, Double> entry : top) {
            sender.sendMessage(ChatColor.GOLD + "#" + pos + " " + ChatColor.WHITE + manager.getName(entry.getKey())
                    + ChatColor.GRAY + " - " + ChatColor.GREEN + type.formatValue(entry.getValue()));
            pos++;
        }
        if (sender instanceof Player) {
            UUID uuid = ((Player) sender).getUniqueId();
            List<Map.Entry<UUID, Double>> all = manager.getTop(-1);
            int rank = -1;
            for (int i = 0; i < all.size(); i++) {
                if (all.get(i).getKey().equals(uuid)) {
                    rank = i + 1;
                    break;
                }
            }
            if (rank > 0) {
                sender.sendMessage(ChatColor.AQUA + "Tu posicion: #" + rank + " (" + type.formatValue(manager.get(uuid)) + ")");
            }
        }
        return true;
    }
}

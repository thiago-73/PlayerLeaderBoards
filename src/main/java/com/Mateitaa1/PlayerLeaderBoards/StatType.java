package com.Mateitaa1.PlayerLeaderBoards;

public enum StatType {
    KILLS("kills", "Kills", "kills.yml", "&c&lTOP KILLS"),
    DEATHS("deaths", "Muertes", "deaths.yml", "&4&lTOP MUERTES"),
    PLAYTIME("playtime", "Tiempo Jugado", "playtime.yml", "&b&lTOP TIEMPO JUGADO"),
    MESSAGES("messages", "Mensajes", "messages.yml", "&e&lTOP MENSAJES"),
    BLOCKSMINED("blocksmined", "Bloques Minados", "blocksmined.yml", "&a&lTOP BLOQUES MINADOS"),
    DISTANCEWALKED("distancewalked", "Distancia Caminada", "distancewalked.yml", "&d&lTOP DISTANCIA CAMINADA");

    private final String id;
    private final String displayName;
    private final String fileName;
    private final String title;

    StatType(String id, String displayName, String fileName, String title) {
        this.id = id;
        this.displayName = displayName;
        this.fileName = fileName;
        this.title = title;
    }

    public String getId() {
        return id;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getFileName() {
        return fileName;
    }

    public String getTitle() {
        return title;
    }

    public static StatType fromId(String id) {
        for (StatType t : values()) {
            if (t.id.equalsIgnoreCase(id)) {
                return t;
            }
        }
        return null;
    }

    /** Convierte el valor crudo guardado en un texto legible. */
    public String formatValue(double value) {
        switch (this) {
            case PLAYTIME:
                long totalMinutes = (long) value;
                long hours = totalMinutes / 60;
                long minutes = totalMinutes % 60;
                return hours + "h " + minutes + "m";
            case DISTANCEWALKED:
                return String.format("%.0f bloques", value);
            default:
                return String.format("%.0f", value);
        }
    }
}

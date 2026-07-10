package com.sunlitvalley.menucards.client;

import net.minecraft.resources.ResourceLocation;

public enum MenuCard {
    TOWN_HOME("town_home", 0),
    SPAWN("spawn", 1),
    SELL("sell", 2),
    HELP("help", 3),
    SKULL_CAVE("skull_cave", 4);

    private final String id;
    private final int sortIndex;
    private final ResourceLocation texture;
    private final String loreKey;

    MenuCard(String id, int sortIndex) {
        this.id = id;
        this.sortIndex = sortIndex;
        this.texture = new ResourceLocation("menucards", "textures/gui/" + id + ".png");
        this.loreKey = "menucards.lore." + id;
    }

    public String getId() { return id; }
    public int getSortIndex() { return sortIndex; }
    public ResourceLocation getTexture() { return texture; }
    public String getLoreKey() { return loreKey; }
}

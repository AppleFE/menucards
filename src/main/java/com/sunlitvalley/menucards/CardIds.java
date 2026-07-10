package com.sunlitvalley.menucards;

import java.util.Set;

/**
 * Canonical set of valid card IDs shared between client and server.
 * This is the single source of truth — both MenuCard (client) and
 * ServerActionHandler (server) must use these exact IDs.
 */
public final class CardIds {
    public static final String TOWN_HOME = "town_home";
    public static final String SPAWN = "spawn";
    public static final String SELL = "sell";
    public static final String HELP = "help";
    public static final String SKULL_CAVE = "skull_cave";

    public static final Set<String> ALL = Set.of(TOWN_HOME, SPAWN, SELL, HELP, SKULL_CAVE);

    private CardIds() {}
}

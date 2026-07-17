package com.sunlitvalley.menucards.integration.shipping;

import dev.latvian.mods.kubejs.KubeJSPlugin;
import dev.latvian.mods.kubejs.script.ScriptType;
import dev.latvian.mods.kubejs.util.ClassFilter;

/** Exposes only the shipping bridge package to server-side KubeJS policy code. */
public final class MenuCardsKubeJSPlugin extends KubeJSPlugin {
    @Override
    public void registerClasses(ScriptType type, ClassFilter filter) {
        if (type == ScriptType.SERVER) {
            filter.allow("com.sunlitvalley.menucards.integration.shipping");
        }
    }
}

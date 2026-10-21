package dev.csmc.skin;

import net.minecraft.world.item.ItemDisplayContext;

/**
 * Which composite a render needs. GUI icons (case reel, skin picker, inventory) are at most a few hundred device
 * pixels wide, so they use a small thumbnail from their own pool; every world/hand context keeps the full texture.
 */
public enum SkinResolution {
    THUMBNAIL(256),
    FULL(1024);

    public final int edge;

    SkinResolution(int edge) { this.edge = edge; }

    public static SkinResolution of(ItemDisplayContext context) {
        return context == ItemDisplayContext.GUI ? THUMBNAIL : FULL;
    }
}

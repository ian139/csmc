package dev.csmc.data;

import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

/** Self-authored fixtures shaped like the exporter's manifests; no exported content. */
public class CacheAssetsTest {
    private static final String HD = "weapons/models/x/weapon_x.vmdl_c.body_hd";
    private static final String LEGACY = "weapons/models/x/weapon_x.vmdl_c.body_legacy";
    private static final String HOLSTER = "weapons/models/x/weapon_x.vmdl_c.eholster";

    @Test
    public void meshSetPrefersHdThenLegacyAndKeepsUngroupedNodes() {
        List<String> names = List.of(LEGACY, HD, HOLSTER, "plain_mesh");
        assertEquals("body_hd", MeshSets.choose(names, MeshSets.DEFAULT_PREFERENCE));
        assertEquals("body_legacy", MeshSets.choose(names, List.of("body_legacy")));
        assertEquals("body_legacy", MeshSets.choose(List.of(LEGACY, HOLSTER), MeshSets.DEFAULT_PREFERENCE));
        assertEquals("first group when no preference matches", "body_legacy",
                MeshSets.choose(List.of(LEGACY, HOLSTER), List.of("missing")));
        assertNull(MeshSets.choose(List.of("plain_mesh"), MeshSets.DEFAULT_PREFERENCE));
        assertTrue(MeshSets.selected(HD, "body_hd"));
        assertFalse(MeshSets.selected(LEGACY, "body_hd"));
        assertFalse(MeshSets.selected(HOLSTER, "body_hd"));
        assertTrue(MeshSets.selected("plain_mesh", "body_hd"));
        assertTrue(MeshSets.selected(LEGACY, null));
    }

    @Test
    public void clipMappingUsesManifestThenClipNames() {
        CacheManifest manifest = CacheManifest.parse("""
                {"weapons": {
                  "ak47": {"status": "ok", "animations": {"fire": "shoot", "reload": "reload"},
                    "models": {"world": {"file": "models/ak47/ak47.gltf", "clips": ["dropped", "reload", "shoot"],
                      "meshes": ["a.vmdl_c.body_legacy", "a.vmdl_c.body_hd"]}}},
                  "glock": {"status": "ok",
                    "models": {"world": {"file": "models/glock/glock.gltf", "clips": ["Shoot", "Reload", "deploy"]}}},
                  "m14": {"status": "failed", "models": {}}
                }}""");
        CacheManifest.Weapon ak = manifest.weapon("ak47");
        assertEquals("shoot", ak.clipFor("fire"));
        assertEquals("reload", ak.clipFor("reload"));
        assertNull("no draw clip in the model", ak.clipFor("draw"));
        assertEquals(List.of("a.vmdl_c.body_legacy", "a.vmdl_c.body_hd"), ak.meshes());
        CacheManifest.Weapon glock = manifest.weapon("glock");
        assertEquals("Shoot", glock.clipFor("fire"));
        assertEquals("deploy", glock.clipFor("draw"));
        assertNull(manifest.weapon("m14").clipFor("fire"));
        assertNull(manifest.weapon("missing"));
        assertNull(CacheManifest.parse("{}").weapon("ak47"));
    }

    @Test
    public void soundManifestSplitsNearDistantSilencedAndOrdersReloadParts() {
        GunSoundManifest ak = GunSoundManifest.parse("""
                {"events": {
                  "Weapon_AK47.Single": {"action": "fire", "files": ["fire.ogg", "fire_2.ogg"]},
                  "Weapon_AK47.SingleDistant": {"action": "fire", "files": ["fire_singledistant.ogg"]},
                  "Weapon_AK47.Draw": {"action": "draw", "files": ["draw.ogg"]},
                  "Weapon_AK47.BoltPull": {"action": "reload", "files": ["reload_boltpull.ogg"]},
                  "Weapon_AK47.Clipin": {"action": "reload", "files": ["reload_clipin.ogg"]},
                  "Weapon_AK47.Clipout": {"action": "reload", "files": ["reload_clipout.ogg"]},
                  "Weapon_AK47.BoltPull_Q": {"action": "reload", "files": ["reload_boltpull_q.ogg"]},
                  "Weapon_AK47.AddAmmo": {"action": "reload", "files": ["reload_addammo.ogg"]},
                  "Weapon_AK47.Evil": {"action": "fire", "files": ["../escape.ogg", "x.wav"]}
                }}""");
        assertEquals(List.of("fire.ogg", "fire_2.ogg"), ak.fireNear());
        assertEquals(List.of("fire_singledistant.ogg"), ak.fireDistant());
        assertEquals(List.of("draw.ogg"), ak.draw());
        List<GunSoundManifest.ReloadPart> parts = ak.reloadParts();
        assertEquals(List.of("reload_clipout.ogg", "reload_clipin.ogg", "reload_boltpull.ogg"),
                parts.stream().map(p -> p.files().get(0)).toList());
        for (int i = 1; i < parts.size(); i++) assertTrue(parts.get(i - 1).phase() < parts.get(i).phase());
        assertFalse("path traversal and non-OGG files are dropped", ak.allFiles().contains("../escape.ogg"));
        assertFalse(ak.allFiles().contains("x.wav"));

        GunSoundManifest usp = GunSoundManifest.parse("""
                {"events": {
                  "Weapon_USP.Single": {"action": "fire", "files": ["fire.ogg"]},
                  "Weapon_USP.SilencedShot": {"action": "fire", "files": ["fire_silencedshot.ogg"]},
                  "Weapon_USP.SingleDistant": {"action": "fire", "files": ["fire_singledistant.ogg"]}
                }}""");
        assertEquals("silenced weapons fire silenced by default", List.of("fire_silencedshot.ogg"), usp.fireNear());
        assertEquals(List.of(), usp.fireDistant());
        assertTrue(usp.reloadParts().isEmpty());

        GunSoundManifest nova = GunSoundManifest.parse("""
                {"events": {
                  "Weapon_Nova.Insertshell": {"action": "reload", "files": ["reload_insertshell.ogg"]},
                  "Weapon_Nova.Pump": {"action": "reload", "files": ["reload_pump.ogg"]},
                  "Weapon_Nova.AddAmmo": {"action": "reload", "files": ["reload_addammo.ogg"]}
                }}""");
        assertEquals(List.of("reload_insertshell.ogg", "reload_pump.ogg"),
                nova.reloadParts().stream().map(p -> p.files().get(0)).toList());
    }

    @Test
    public void soundManifestKeepsScopeDirectionsAndPreloadsTheirFiles() {
        GunSoundManifest scoped = GunSoundManifest.parse("""
                {"events": {
                  "Weapon_SSG08.Single": {"action": "fire", "files": ["fire.ogg"]},
                  "Weapon_SSG08.Zoom": {"action": "zoom_in", "files": ["zoom_in_zoom.ogg", "shared.ogg"]},
                  "Weapon_SSG08.ZoomOut": {"action": "zoom_out", "files": ["zoom_out_zoomout.ogg", "shared.ogg"]},
                  "Weapon_SSG08.ZoomAlternate": {"action": "zoom_in", "files": ["zoom_in_zoom.ogg"]}
                }}""");
        assertEquals(List.of("zoom_in_zoom.ogg", "shared.ogg"), scoped.zoomIn());
        assertEquals(List.of("zoom_out_zoomout.ogg", "shared.ogg"), scoped.zoomOut());
        assertEquals(List.of("fire.ogg"), scoped.fireNear());
        assertTrue(scoped.draw().isEmpty());
        assertTrue(scoped.reloadParts().isEmpty());
        assertEquals(List.of("fire.ogg", "zoom_in_zoom.ogg", "shared.ogg", "zoom_out_zoomout.ogg"),
                List.copyOf(scoped.allFiles()));

        GunSoundManifest awp = GunSoundManifest.parse("""
                {"events": {
                  "Weapon_AWP.Zoom": {"action": "zoom_in", "files": ["shared.ogg"]},
                  "Weapon_AWP.ZoomOut": {"action": "zoom_out", "files": ["shared.ogg"]}
                }}""");
        assertEquals(List.of("shared.ogg"), awp.zoomIn());
        assertEquals(List.of("shared.ogg"), awp.zoomOut());
        assertEquals(List.of("shared.ogg"), List.copyOf(awp.allFiles()));
    }

    @Test
    public void soundManifestDropsInvalidAndMissingScopeFiles() {
        GunSoundManifest invalid = GunSoundManifest.parse("""
                {"events": {
                  "Weapon_AWP.Zoom": {"action": "zoom_in", "files": ["../escape.ogg", ".hidden.ogg", "zoom.wav", null, {}]},
                  "Weapon_AWP.ZoomOut": {"action": "zoom_out"},
                  "Weapon_AWP.MissingArray": {"action": "zoom_in", "files": "zoom.ogg"},
                  "Weapon_AWP.Empty": {"action": "zoom_out", "files": []},
                  "Weapon_AWP.NoAction": {"files": ["zoom.ogg"]},
                  "Weapon_AWP.Unknown": {"action": "zoom", "files": ["zoom.ogg"]}
                }}""");
        assertTrue(invalid.zoomIn().isEmpty());
        assertTrue(invalid.zoomOut().isEmpty());
        assertTrue(invalid.allFiles().isEmpty());
        assertTrue(GunSoundManifest.parse("{}").zoomIn().isEmpty());
        assertTrue(GunSoundManifest.EMPTY.zoomOut().isEmpty());
    }
}

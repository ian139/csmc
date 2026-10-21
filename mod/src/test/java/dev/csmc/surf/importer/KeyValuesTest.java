package dev.csmc.surf.importer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class KeyValuesTest {
    private static final ImportLimits LIMITS = ImportLimits.DEFAULT;

    @Test
    public void parsesNestedBlocksPairsAndComments() {
        KeyValues.Node root = KeyValues.parse("""
                // header comment
                world
                {
                    "classname" "worldspawn" // trailing comment
                    bare value
                    solid { "id" "7" side { "plane" "(0 0 0) (1 0 0) (0 1 0)" } }
                }
                entity { "classname" "info_target" }
                """, LIMITS);
        assertEquals(2, root.children().size());
        KeyValues.Node world = root.child("WORLD");
        assertEquals("worldspawn", world.get("ClassName"));
        assertEquals("value", world.get("bare"));
        assertEquals("7", world.child("solid").get("id"));
        assertEquals("(0 0 0) (1 0 0) (0 1 0)", world.child("solid").child("side").get("plane"));
        assertNull(world.get("missing"));
        assertEquals(1, root.children("entity").size());
    }

    @Test
    public void rejectsUnterminatedStringWithLineNumber() {
        SurfImportException e = assertThrows(SurfImportException.class,
                () -> KeyValues.parse("world\n{\n\"classname\" \"worldspawn\n}\n", LIMITS));
        assertTrue(e.getMessage(), e.getMessage().startsWith("line 3"));
    }

    @Test
    public void rejectsUnbalancedBraces() {
        assertThrows(SurfImportException.class, () -> KeyValues.parse("world { solid { }", LIMITS));
        assertThrows(SurfImportException.class, () -> KeyValues.parse("world { } }", LIMITS));
        assertThrows(SurfImportException.class, () -> KeyValues.parse("{ }", LIMITS));
        assertThrows(SurfImportException.class, () -> KeyValues.parse("world { \"key\" }", LIMITS));
    }

    @Test
    public void enforcesDepthNodeTokenAndSizeLimits() {
        String deep = "a {".repeat(100) + "}".repeat(100);
        assertTrue(assertThrows(SurfImportException.class, () -> KeyValues.parse(deep, LIMITS)).getMessage()
                .contains("nested deeper"));
        ImportLimits small = new ImportLimits(64, 64, 3, 2, 8, 10, 10, 10, 16384, 10);
        assertThrows(SurfImportException.class, () -> KeyValues.parse("a { } b { } c { }", small));
        assertThrows(SurfImportException.class, () -> KeyValues.parse("a { \"k\" \"123456789\" }", small));
        assertThrows(SurfImportException.class, () -> KeyValues.parse("a { k 1 l 2 m 3 }", small));
        assertThrows(SurfImportException.class, () -> KeyValues.parse("a { }" + " ".repeat(80), small));
    }
}

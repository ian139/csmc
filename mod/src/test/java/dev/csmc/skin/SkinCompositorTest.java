package dev.csmc.skin;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.imageio.ImageIO;
import org.junit.Test;
import static org.junit.Assert.*;

public class SkinCompositorTest {
    private Path fixture(int style) throws Exception {
        Path dir = Files.createTempDirectory("csmc-self-authored-skin-");
        String[] names = {"color", "masks", "cavity", "pattern", "wear", "grunge"};
        int[] colors = {0xff303030, 0xffff0000, 0xff80ff00, 0xffff8000, 0xff808080, 0xffffffff};
        for (int i = 0; i < names.length; i++) {
            BufferedImage image = new BufferedImage(4, 4, BufferedImage.TYPE_INT_ARGB);
            for (int y = 0; y < 4; y++) for (int x = 0; x < 4; x++) image.setRGB(x, y, colors[i]);
            ImageIO.write(image, "PNG", dir.resolve(names[i] + ".png").toFile());
        }
        JsonObject params = JsonParser.parseString("{\"effective_style\":" + style + ",\"params\":{\"g_vColor0\":[0,0,1],\"g_vColor1\":[1,0,0]},\"inputs\":{}}").getAsJsonObject();
        for (String name : names) params.getAsJsonObject("inputs").addProperty(name, name + ".png");
        Files.writeString(dir.resolve("params.json"), params.toString());
        return dir;
    }

    @Test public void customPaintUsesRgbNotPatternAlphaAsOpacity() throws Exception {
        BufferedImage image = SkinCompositor.compose(fixture(7), new SkinData(1, 0, 42), 1024);
        assertEquals(0xffff8000, image.getRGB(0, 0));
    }

    @Test public void packedMaskAlphaDoesNotEraseRedChannelOrMakeWeaponTransparent() throws Exception {
        Path dir = fixture(1);
        BufferedImage mask = new BufferedImage(4, 4, BufferedImage.TYPE_INT_ARGB);
        BufferedImage base = new BufferedImage(4, 4, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 4; y++) for (int x = 0; x < 4; x++) {
            mask.setRGB(x, y, 0x00ff0000);
            base.setRGB(x, y, 0x00303030);
        }
        ImageIO.write(mask, "PNG", dir.resolve("masks.png").toFile());
        ImageIO.write(base, "PNG", dir.resolve("color.png").toFile());
        assertEquals(0xffff0000, SkinCompositor.compose(dir, new SkinData(1, 0, 0), 1024).getRGB(0, 0));
    }

    @Test public void solidUsesWeaponPaintByNumberMaskAndWearRevealsBase() throws Exception {
        Path dir = fixture(1);
        assertEquals(0xffff0000, SkinCompositor.compose(dir, new SkinData(1, 0, 0), 1024).getRGB(0, 0));
        int worn = SkinCompositor.compose(dir, new SkinData(1, 1, 0), 1024).getRGB(0, 0);
        assertEquals(0x303030, worn & 0xffffff);
    }

    /** Two columns: flat surface (cavity red 0) on the left, exposed edge (cavity red 255) on the right. */
    private Path edgeFixture(int patternAlpha) throws Exception {
        Path dir = fixture(7);
        BufferedImage cavity = new BufferedImage(2, 1, BufferedImage.TYPE_INT_ARGB);
        cavity.setRGB(0, 0, 0xff00ff00);
        cavity.setRGB(1, 0, 0xffffff00);
        ImageIO.write(cavity, "PNG", dir.resolve("cavity.png").toFile());
        BufferedImage pattern = new BufferedImage(2, 1, BufferedImage.TYPE_INT_ARGB);
        for (int x = 0; x < 2; x++) pattern.setRGB(x, 0, (patternAlpha << 24) | 0xff8000);
        ImageIO.write(pattern, "PNG", dir.resolve("pattern.png").toFile());
        BufferedImage base = new BufferedImage(2, 1, BufferedImage.TYPE_INT_ARGB);
        for (int x = 0; x < 2; x++) base.setRGB(x, 0, 0xff303030);
        ImageIO.write(base, "PNG", dir.resolve("color.png").toFile());
        return dir;
    }

    @Test public void battleScarredWearStripsEdgesButKeepsPaintOnFlatSurfaces() throws Exception {
        BufferedImage worn = SkinCompositor.compose(edgeFixture(255), new SkinData(1, .6f, 0), 1024);
        assertEquals("flat surface keeps paint", 0xffff8000, worn.getRGB(0, 0));
        assertEquals("exposed edge shows the weapon", 0xff303030, worn.getRGB(1, 0));
    }

    @Test public void factoryNewKeepsPaintEvenOnEdges() throws Exception {
        BufferedImage fresh = SkinCompositor.compose(edgeFixture(255), new SkinData(1, .02f, 0), 1024);
        assertEquals(0xffff8000, fresh.getRGB(0, 0));
        assertEquals(0xffff8000, fresh.getRGB(1, 0));
    }

    @Test public void darkCustomPaintAlphaIsDurableAtFullWear() throws Exception {
        BufferedImage worn = SkinCompositor.compose(edgeFixture(0), new SkinData(1, 1, 0), 1024);
        assertEquals("alpha 0 never wears, even on an edge", 0xffff8000, worn.getRGB(1, 0));
    }

    @Test public void seedIsDeterministicAndOutputIsBounded() throws Exception {
        Path dir = fixture(2);
        BufferedImage a = SkinCompositor.compose(dir, new SkinData(1, .2f, 987), 2);
        BufferedImage b = SkinCompositor.compose(dir, new SkinData(1, .2f, 987), 2);
        assertEquals(2, a.getWidth());
        assertEquals(a.getRGB(1, 1), b.getRGB(1, 1));
    }
}

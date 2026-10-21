package dev.csmc.client.sound;

import com.mojang.blaze3d.audio.SoundBuffer;
import dev.csmc.CsmcMod;
import dev.csmc.data.CachePaths;
import dev.csmc.viewmodel.PcmDownmix;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletionException;
import java.util.regex.Pattern;
import javax.sound.sampled.AudioFormat;
import net.minecraft.client.resources.sounds.Sound;
import net.minecraft.client.sounds.JOrbisAudioStream;
import net.minecraft.resources.ResourceLocation;

/**
 * Sounds under the {@value #NAMESPACE} namespace are read from the local CS2 export cache instead of resource
 * packs ({@code SoundBufferLibraryMixin}): {@code csmc_cache:ak47/fire} → {@code <cache>/sounds/ak47/fire.ogg}.
 * Decoding uses vanilla's OGG reader, so playback keeps vanilla channels, volume categories and attenuation.
 * {@value #MONO_NAMESPACE} names the same files downmixed to one channel, so OpenAL spatializes them; its
 * distinct buffer-cache key never shares a decode with the file's {@value #NAMESPACE} buffer.
 */
public final class CachedSoundFiles {
    public static final String NAMESPACE = "csmc_cache";
    public static final String MONO_NAMESPACE = "csmc_cache_mono";
    private static final Pattern PATH = Pattern.compile("sounds/[a-z0-9_]+/[a-z0-9_][a-z0-9_.-]*\\.ogg");
    /** Logs every decode at INFO (set by the dev preload hook). */
    static volatile boolean verbose;

    private CachedSoundFiles() {}

    /** Whether {@code namespace} is served from the export cache. */
    public static boolean cached(String namespace) {
        return NAMESPACE.equals(namespace) || MONO_NAMESPACE.equals(namespace);
    }

    /** Sound id for a cache file name such as {@code fire_2.ogg}. */
    static ResourceLocation location(String weapon, String file) {
        return location(NAMESPACE, weapon, file);
    }

    /** Mono-downmixed sound id for a cache file name. */
    static ResourceLocation monoLocation(String weapon, String file) {
        return location(MONO_NAMESPACE, weapon, file);
    }

    private static ResourceLocation location(String namespace, String weapon, String file) {
        return ResourceLocation.fromNamespaceAndPath(namespace, weapon + "/" + file.substring(0, file.length() - ".ogg".length()));
    }

    /** Validated cache file for a buffer path ({@link Sound#getPath()}); never escapes {@code <cache>/sounds}. */
    static Path file(ResourceLocation bufferPath) {
        String path = bufferPath.getPath();
        if (!cached(bufferPath.getNamespace()) || !PATH.matcher(path).matches()) {
            throw new IllegalArgumentException("Not a cached sound: " + bufferPath);
        }
        Path sounds = CachePaths.root().resolve("sounds").toAbsolutePath().normalize();
        Path file = sounds.getParent().resolve(path).normalize();
        if (!file.startsWith(sounds)) throw new IllegalArgumentException("Cached sound escapes cache: " + bufferPath);
        return file;
    }

    /** Decodes on the caller's (I/O) thread; failures complete the vanilla buffer future exceptionally. */
    public static SoundBuffer decode(ResourceLocation bufferPath) {
        Path file = file(bufferPath);
        long started = System.nanoTime();
        try (InputStream input = Files.newInputStream(file); JOrbisAudioStream stream = new JOrbisAudioStream(input)) {
            ByteBuffer pcm = stream.readAll();
            AudioFormat format = stream.getFormat();
            if (MONO_NAMESPACE.equals(bufferPath.getNamespace()) && format.getChannels() != 1) {
                if (format.getSampleSizeInBits() != 16 || format.getEncoding() != AudioFormat.Encoding.PCM_SIGNED) {
                    throw new IOException("Cannot downmix " + format + " to mono");
                }
                pcm = PcmDownmix.toMono16(pcm, format.getChannels(), format.isBigEndian());
                format = new AudioFormat(format.getSampleRate(), 16, 1, true, format.isBigEndian());
            }
            if (verbose) {
                CsmcMod.LOGGER.info("[csmc sound] Decoded {} from {}: {} Hz, {} ch, {} PCM bytes ({} s) in {} ms", bufferPath, file,
                        (int) format.getSampleRate(), format.getChannels(), pcm.remaining(),
                        String.format(java.util.Locale.ROOT, "%.2f", pcm.remaining() / (double) format.getFrameSize() / format.getSampleRate()),
                        (System.nanoTime() - started) / 1_000_000);
            }
            return new SoundBuffer(pcm, format);
        } catch (IOException e) {
            CsmcMod.LOGGER.warn("[csmc sound] Cannot decode {}", file, e);
            throw new CompletionException(e);
        }
    }
}

package dev.csmc.client.thirdperson.guns;

import dev.csmc.viewmodel.ViewmodelCue;

/** Existing renderer cue with its immutable client receipt timestamp; also usable by other weapon pose providers. */
public interface TimedGunCue extends ViewmodelCue {
    long started();
}

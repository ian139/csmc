package dev.csmc.client.gltf;

import java.io.IOException;

/** Thrown when a glTF asset is malformed, unsafe, or uses a feature this reader cannot render. */
public final class GltfException extends IOException {
    private static final long serialVersionUID = 1L;

    public GltfException(String message) {
        super(message);
    }

    public GltfException(String message, Throwable cause) {
        super(message, cause);
    }
}

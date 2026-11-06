package com.pdfgen.application;

import java.util.List;

/** Port: binary assets referenced by documents (e.g. the profile photo), addressed relative to the data root. */
public interface AssetSource {

    /**
     * @throws DocumentLoadException if the path escapes the data root or the asset cannot be read
     */
    byte[] load(String relativePath);

    /** Images that can be used as a photo, e.g. {@code assets/photo.jpg}, sorted. */
    List<String> listImages();

    /**
     * Stores {@code fileName} (already checked and sanitized by {@link PhotoLibrary}: {@code [a-z0-9-]+.(jpg|png)})
     * under {@code assets/} and returns its path relative to the data root (e.g. {@code assets/photo-2.jpg}).
     * Never overwrites: identical content reuses the existing file, different content gets a free name.
     */
    String saveImage(String fileName, byte[] content);
}

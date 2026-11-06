package com.pdfgen.application;

import com.pdfgen.domain.DocumentException;
import com.pdfgen.domain.tree.IdGenerator;

import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * Use cases for photos (ADR-0024): upload a JPEG/PNG into {@code data/assets/}, list and serve them.
 * The checks are here, framework-free; {@link AssetSource} only stores and reads bytes.
 */
public final class PhotoLibrary {

    /** Photos are scaled down to 600 px when rendered; 10 MB is plenty for any camera picture. */
    public static final int MAX_BYTES = 10 * 1024 * 1024;

    private static final byte[] PNG_SIGNATURE = {(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10};

    private final AssetSource assets;

    public PhotoLibrary(AssetSource assets) {
        this.assets = assets;
    }

    public List<String> list() {
        return assets.listImages();
    }

    /**
     * Stores an uploaded photo and returns its path relative to the data root (e.g. {@code assets/me.jpg}).
     * The type comes from the file's first bytes, never from its name. JPEG metadata (EXIF with GPS position,
     * XMP, comments) is removed without re-encoding.
     *
     * @throws UnsupportedImageException if it is not a valid JPEG or PNG
     * @throws ImageTooLargeException    above {@value #MAX_BYTES} bytes
     */
    public String upload(String originalName, byte[] content) {
        if (content.length > MAX_BYTES) {
            throw new ImageTooLargeException();
        }
        ImageType type = detect(content).orElseThrow(
                () -> new UnsupportedImageException("Only JPEG and PNG photos are supported"));
        byte[] cleaned = type == ImageType.JPEG ? stripJpegMetadata(content) : content;
        return assets.saveImage(fileName(originalName, type), cleaned);
    }

    /** An image for the editor's thumbnail. Only paths that {@link #list()} returns are served (allow-list). */
    public Optional<Image> image(String path) {
        if (path == null || !assets.listImages().contains(path)) {
            return Optional.empty();
        }
        byte[] content = assets.load(path);
        return detect(content).map(type -> new Image(content, type.mediaType));
    }

    public record Image(byte[] content, String mediaType) {
    }

    // ---- pure helpers (package-private for tests) ------------------------------------------------

    enum ImageType {
        JPEG("jpg", "image/jpeg"), PNG("png", "image/png");

        final String extension;
        final String mediaType;

        ImageType(String extension, String mediaType) {
            this.extension = extension;
            this.mediaType = mediaType;
        }
    }

    static Optional<ImageType> detect(byte[] content) {
        if (content.length >= 3 && (content[0] & 0xFF) == 0xFF && (content[1] & 0xFF) == 0xD8
                && (content[2] & 0xFF) == 0xFF) {
            return Optional.of(ImageType.JPEG);
        }
        if (content.length >= PNG_SIGNATURE.length
                && Arrays.equals(Arrays.copyOf(content, PNG_SIGNATURE.length), PNG_SIGNATURE)) {
            return Optional.of(ImageType.PNG);
        }
        return Optional.empty();
    }

    /** {@code C:\fakepath\Poză profil.JPEG} becomes {@code poza-profil.jpg}; nothing but [a-z0-9-] survives. */
    static String fileName(String originalName, ImageType type) {
        String name = originalName == null ? "" : originalName.replace('\\', '/');
        name = name.substring(name.lastIndexOf('/') + 1);
        int dot = name.lastIndexOf('.');
        String stem = dot > 0 ? name.substring(0, dot) : name;
        String slug = IdGenerator.slug(stem, 8);
        if (slug.isBlank() || slug.equals("item") && !stem.toLowerCase().contains("item")) {
            slug = "photo";
        }
        return slug + "." + type.extension;
    }

    /**
     * Removes the JPEG segments that carry metadata: APP1 (EXIF, including GPS position and camera, and XMP),
     * APP3–APP13 and APP15 (IPTC, Photoshop, vendor data) and COM. Keeps APP0 (JFIF), APP2 (ICC colour
     * profile), APP14 (Adobe colour transform) and all image data. Lossless: the pixels are not touched.
     * The EXIF orientation tag goes too, so the PDF shows the picture exactly as the editor thumbnail does.
     */
    static byte[] stripJpegMetadata(byte[] jpeg) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(jpeg.length);
        out.write(0xFF);
        out.write(0xD8);
        int i = 2;
        while (i < jpeg.length) {
            if ((jpeg[i] & 0xFF) != 0xFF) {
                throw damaged();
            }
            int marker = i + 1 < jpeg.length ? jpeg[i + 1] & 0xFF : -1;
            if (marker == 0xFF) { // fill byte
                i++;
                continue;
            }
            if (marker == 0xD9 || marker == 0xDA) { // end of image, or start of scan: the rest is image data
                out.write(jpeg, i, jpeg.length - i);
                return out.toByteArray();
            }
            if (marker == 0x01 || (marker >= 0xD0 && marker <= 0xD7)) { // markers without a length
                out.write(jpeg, i, 2);
                i += 2;
                continue;
            }
            if (marker < 0 || i + 3 >= jpeg.length) {
                throw damaged();
            }
            int length = ((jpeg[i + 2] & 0xFF) << 8) | (jpeg[i + 3] & 0xFF);
            int end = i + 2 + length;
            if (length < 2 || end > jpeg.length) {
                throw damaged();
            }
            boolean metadata = marker == 0xFE || marker == 0xE1 || (marker >= 0xE3 && marker <= 0xED) || marker == 0xEF;
            if (!metadata) {
                out.write(jpeg, i, end - i);
            }
            i = end;
        }
        throw damaged();
    }

    private static UnsupportedImageException damaged() {
        return new UnsupportedImageException("The JPEG file is damaged or incomplete");
    }

    /** The upload is not a usable JPEG/PNG image (HTTP 415). */
    public static final class UnsupportedImageException extends DocumentException {
        public UnsupportedImageException(String message) {
            super(message);
        }
    }

    /** The upload is larger than {@value PhotoLibrary#MAX_BYTES} bytes (HTTP 413). */
    public static final class ImageTooLargeException extends DocumentException {
        public ImageTooLargeException() {
            super("The photo is larger than " + MAX_BYTES / (1024 * 1024) + " MB");
        }
    }
}

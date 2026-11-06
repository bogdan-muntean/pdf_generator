package com.pdfgen.application;

import com.pdfgen.adapters.storage.FileDocumentRepository;
import com.pdfgen.application.PhotoLibrary.ImageTooLargeException;
import com.pdfgen.application.PhotoLibrary.ImageType;
import com.pdfgen.application.PhotoLibrary.UnsupportedImageException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Photo upload (ADR-0024): type from magic bytes, safe names, no overwrite, JPEG metadata removed. */
class PhotoLibraryTest {

    @TempDir
    Path data;

    private PhotoLibrary library() {
        return new PhotoLibrary(new FileDocumentRepository(data));
    }

    static byte[] jpeg(int rgb) throws IOException {
        BufferedImage image = new BufferedImage(4, 4, BufferedImage.TYPE_INT_RGB);
        image.setRGB(0, 0, rgb);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "jpg", out);
        return out.toByteArray();
    }

    static byte[] png() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB), "png", out);
        return out.toByteArray();
    }

    /** Inserts an APP1 (EXIF) segment with a fake GPS text and a COM segment right after SOI. */
    static byte[] withMetadata(byte[] jpeg) {
        byte[] exif = "Exif\0\0GPS 46.77N 23.59E".getBytes();
        byte[] comment = "secret comment".getBytes();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(jpeg, 0, 2);
        segment(out, 0xE1, exif);
        segment(out, 0xFE, comment);
        out.write(jpeg, 2, jpeg.length - 2);
        return out.toByteArray();
    }

    private static void segment(ByteArrayOutputStream out, int marker, byte[] payload) {
        int length = payload.length + 2;
        out.write(0xFF);
        out.write(marker);
        out.write(length >> 8);
        out.write(length & 0xFF);
        out.write(payload, 0, payload.length);
    }

    @Test
    void detectsTheRealTypeFromTheFirstBytes() throws IOException {
        assertThat(PhotoLibrary.detect(jpeg(0))).contains(ImageType.JPEG);
        assertThat(PhotoLibrary.detect(png())).contains(ImageType.PNG);
        assertThat(PhotoLibrary.detect("<svg/>".getBytes())).isEmpty();
        assertThat(PhotoLibrary.detect(new byte[0])).isEmpty();
    }

    @Test
    void sanitizesFileNamesAndTakesTheExtensionFromTheContent() {
        assertThat(PhotoLibrary.fileName("C:\\fakepath\\Poză profil.JPEG", ImageType.JPEG)).isEqualTo("poza-profil.jpg");
        assertThat(PhotoLibrary.fileName("../../evil.png.exe", ImageType.PNG)).isEqualTo("evil-png.png");
        assertThat(PhotoLibrary.fileName("..", ImageType.JPEG)).isEqualTo("photo.jpg");
        assertThat(PhotoLibrary.fileName(null, ImageType.PNG)).isEqualTo("photo.png");
        assertThat(PhotoLibrary.fileName("photo.png", ImageType.JPEG)).isEqualTo("photo.jpg");
    }

    @Test
    void storesInAssetsReusesIdenticalContentAndNeverOverwrites() throws IOException {
        PhotoLibrary library = library();
        byte[] red = jpeg(0xFF0000);
        assertThat(library.upload("me.jpg", red)).isEqualTo("assets/me.jpg");
        assertThat(library.upload("me.jpg", red)).isEqualTo("assets/me.jpg");
        assertThat(library.upload("me.jpg", jpeg(0x00FF00))).isEqualTo("assets/me-2.jpg");
        assertThat(Files.readAllBytes(data.resolve("assets/me.jpg"))).isEqualTo(red);
        assertThat(library.list()).containsExactly("assets/me-2.jpg", "assets/me.jpg");
        try (var files = Files.list(data.resolve("assets"))) {
            assertThat(files.map(p -> p.getFileName().toString())).noneMatch(name -> name.endsWith(".tmp"));
        }
    }

    @Test
    void removesExifGpsAndCommentsButKeepsTheImage() throws IOException {
        byte[] original = jpeg(0x123456);
        byte[] tagged = withMetadata(original);
        assertThat(new String(tagged, java.nio.charset.StandardCharsets.ISO_8859_1)).contains("GPS 46.77N");

        byte[] stripped = PhotoLibrary.stripJpegMetadata(tagged);
        String text = new String(stripped, java.nio.charset.StandardCharsets.ISO_8859_1);
        assertThat(text).doesNotContain("GPS").doesNotContain("secret comment");
        assertThat(stripped).isEqualTo(PhotoLibrary.stripJpegMetadata(original)); // only metadata differed
        assertThat(ImageIO.read(new java.io.ByteArrayInputStream(stripped)).getWidth()).isEqualTo(4);

        // the stored file is the cleaned one, so uploading the tagged and the clean picture gives one file
        PhotoLibrary library = library();
        String first = library.upload("a.jpg", tagged);
        assertThat(library.upload("a.jpg", original)).isEqualTo(first);
    }

    @Test
    void rejectsOtherFilesTooLargeFilesAndDamagedJpegs() {
        PhotoLibrary library = library();
        assertThatThrownBy(() -> library.upload("x.jpg", "GIF89a".getBytes()))
                .isInstanceOf(UnsupportedImageException.class).hasMessageContaining("JPEG and PNG");
        assertThatThrownBy(() -> library.upload("x.jpg", new byte[PhotoLibrary.MAX_BYTES + 1]))
                .isInstanceOf(ImageTooLargeException.class);
        assertThatThrownBy(() -> library.upload("x.jpg", new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE1, 0x7F}))
                .isInstanceOf(UnsupportedImageException.class).hasMessageContaining("damaged");
        assertThat(Files.exists(data.resolve("assets"))).isFalse();
    }

    @Test
    void servesOnlyListedImages() throws IOException {
        PhotoLibrary library = library();
        String path = library.upload("me.png", png());
        assertThat(library.image(path)).hasValueSatisfying(image -> assertThat(image.mediaType()).isEqualTo("image/png"));
        Files.writeString(data.resolve("cv.yaml"), "secret: true");
        assertThat(library.image("cv.yaml")).isEmpty();
        assertThat(library.image("assets/../cv.yaml")).isEmpty();
        assertThat(library.image("../outside.png")).isEmpty();
        assertThat(library.image(null)).isEmpty();
    }
}

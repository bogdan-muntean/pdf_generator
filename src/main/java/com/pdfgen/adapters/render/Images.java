package com.pdfgen.adapters.render;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;
import com.pdfgen.application.DocumentLoadException;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Ellipse2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Base64;
import java.util.Map;

/** Generates the images a theme embeds, as {@code data:} URIs (no network, no temp files). */
final class Images {

    private static final int QR_PIXELS_PER_MODULE = 12;
    private static final int PHOTO_SIZE = 600;

    private Images() {
    }

    /** Black-on-white QR code for {@code url}, sharp at print size (whole pixels per module). */
    static String qrCodeDataUri(String url) {
        try {
            BitMatrix matrix = new QRCodeWriter().encode(url, BarcodeFormat.QR_CODE, 0, 0,
                    Map.of(EncodeHintType.MARGIN, 0, EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M,
                            EncodeHintType.CHARACTER_SET, "UTF-8"));
            int size = matrix.getWidth() * QR_PIXELS_PER_MODULE;
            BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_BYTE_BINARY);
            Graphics2D g = image.createGraphics();
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, size, size);
            g.setColor(Color.BLACK);
            for (int y = 0; y < matrix.getHeight(); y++) {
                for (int x = 0; x < matrix.getWidth(); x++) {
                    if (matrix.get(x, y)) {
                        g.fillRect(x * QR_PIXELS_PER_MODULE, y * QR_PIXELS_PER_MODULE,
                                QR_PIXELS_PER_MODULE, QR_PIXELS_PER_MODULE);
                    }
                }
            }
            g.dispose();
            return pngDataUri(image);
        } catch (WriterException e) {
            throw new IllegalArgumentException("Cannot encode QR code for '" + url + "'", e);
        }
    }

    /** Center-crops the photo to a square and clips it to a circle (transparent corners). */
    static String roundPhotoDataUri(byte[] photo, String name) {
        BufferedImage source;
        try {
            source = ImageIO.read(new ByteArrayInputStream(photo));
        } catch (IOException e) {
            throw new DocumentLoadException("Cannot read photo '" + name + "': " + e.getMessage(), e);
        }
        if (source == null) {
            throw new DocumentLoadException("Photo '" + name + "' is not a supported image (use JPG or PNG)");
        }
        int side = Math.min(source.getWidth(), source.getHeight());
        int offsetX = (source.getWidth() - side) / 2;
        int offsetY = (source.getHeight() - side) / 2;

        BufferedImage round = new BufferedImage(PHOTO_SIZE, PHOTO_SIZE, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = round.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.setClip(new Ellipse2D.Float(0, 0, PHOTO_SIZE, PHOTO_SIZE));
        g.drawImage(source, 0, 0, PHOTO_SIZE, PHOTO_SIZE,
                offsetX, offsetY, offsetX + side, offsetY + side, null);
        g.dispose();
        return pngDataUri(round);
    }

    private static String pngDataUri(BufferedImage image) {
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            ImageIO.write(image, "png", out);
            return "data:image/png;base64," + Base64.getEncoder().encodeToString(out.toByteArray());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}

package com.diagramador.collaboration;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Iterator;
import javax.imageio.ImageIO;
import javax.imageio.ImageReadParam;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Shrinks an image before it is sent to the vision model. On a CPU the cost of an image prompt is the
 * number of image tokens, which grows with the pixel count, so a phone photo or a full-screen capture
 * is reduced to a bounded long side. The stored attachment is never changed: only the copy handed to the
 * model is smaller. Anything that cannot be decoded (or has no reader, such as WebP) is returned as it
 * came, so a failed reduction never fails the ask.
 */
final class ImageDownscaler {

    private static final Logger logger = LoggerFactory.getLogger(ImageDownscaler.class);

    /** The image bytes to send and their media type. */
    record Fitted(byte[] data, String mime) {
    }

    private ImageDownscaler() {
    }

    static Fitted fit(byte[] data, String mime, int maxSide) {
        Fitted original = new Fitted(data, mime);
        String format = formatOf(mime);
        if (maxSide <= 0 || format == null) {
            return original;
        }
        try (ImageInputStream input = ImageIO.createImageInputStream(new ByteArrayInputStream(data))) {
            Iterator<ImageReader> readers = ImageIO.getImageReadersByFormatName(format);
            if (input == null || !readers.hasNext()) {
                return original;
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(input, true, true);
                int width = reader.getWidth(0);
                int height = reader.getHeight(0);
                int longSide = Math.max(width, height);
                if (longSide <= maxSide) {
                    return original;
                }
                // Subsample while decoding so a huge image never has to be held in memory at full size.
                int step = Math.max(1, longSide / (maxSide * 2));
                ImageReadParam parameters = reader.getDefaultReadParam();
                parameters.setSourceSubsampling(step, step, 0, 0);
                BufferedImage decoded = reader.read(0, parameters);
                double scale = (double) maxSide / longSide;
                int targetWidth = Math.max(1, (int) Math.round(width * scale));
                int targetHeight = Math.max(1, (int) Math.round(height * scale));
                // The pixel count drives the image tokens, so the result is used even when it is not lighter.
                return new Fitted(encode(resize(decoded, targetWidth, targetHeight, format), format), mime);
            } finally {
                reader.dispose();
            }
        } catch (IOException | RuntimeException exception) {
            logger.warn("The image could not be reduced; sending it as uploaded: {}", exception.toString());
            return original;
        }
    }

    private static String formatOf(String mime) {
        if ("image/jpeg".equals(mime)) {
            return "jpeg";
        }
        if ("image/png".equals(mime)) {
            return "png";
        }
        return null;
    }

    private static BufferedImage resize(BufferedImage source, int width, int height, String format) {
        boolean keepsAlpha = "png".equals(format) && source.getColorModel().hasAlpha();
        BufferedImage target = new BufferedImage(width, height,
                keepsAlpha ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = target.createGraphics();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            graphics.drawImage(source, 0, 0, width, height, null);
        } finally {
            graphics.dispose();
        }
        return target;
    }

    private static byte[] encode(BufferedImage image, String format) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        if (!ImageIO.write(image, format, out)) {
            throw new IOException("No writer for " + format);
        }
        return out.toByteArray();
    }
}

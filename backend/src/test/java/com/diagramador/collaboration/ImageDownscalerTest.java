package com.diagramador.collaboration;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

class ImageDownscalerTest {

    private static final int MAX_SIDE = 768;

    @Test
    void shrinksALargePngKeepingTheAspectRatioAndTheFormat() throws IOException {
        byte[] original = encode(drawing(2000, 1000, BufferedImage.TYPE_INT_ARGB), "png");

        ImageDownscaler.Fitted fitted = ImageDownscaler.fit(original, "image/png", MAX_SIDE);

        BufferedImage result = decode(fitted.data());
        assertThat(fitted.mime()).isEqualTo("image/png");
        assertThat(result.getWidth()).isEqualTo(768);
        assertThat(result.getHeight()).isEqualTo(384);
    }

    @Test
    void shrinksALargePortraitJpegAlongItsLongSide() throws IOException {
        byte[] original = encode(drawing(900, 3000, BufferedImage.TYPE_INT_RGB), "jpeg");

        ImageDownscaler.Fitted fitted = ImageDownscaler.fit(original, "image/jpeg", MAX_SIDE);

        BufferedImage result = decode(fitted.data());
        assertThat(fitted.mime()).isEqualTo("image/jpeg");
        assertThat(result.getHeight()).isEqualTo(768);
        assertThat(result.getWidth()).isEqualTo(230);
    }

    @Test
    void anImageThatAlreadyFitsIsReturnedUntouched() throws IOException {
        byte[] original = encode(drawing(768, 500, BufferedImage.TYPE_INT_RGB), "png");

        ImageDownscaler.Fitted fitted = ImageDownscaler.fit(original, "image/png", MAX_SIDE);

        assertThat(fitted.data()).isSameAs(original);
        assertThat(fitted.mime()).isEqualTo("image/png");
    }

    @Test
    void bytesThatCannotBeDecodedAreReturnedUntouchedInsteadOfFailingTheAsk() {
        byte[] fakeJpeg = new byte[103];
        fakeJpeg[0] = (byte) 0xFF;
        fakeJpeg[1] = (byte) 0xD8;
        fakeJpeg[2] = (byte) 0xFF;

        ImageDownscaler.Fitted fitted = ImageDownscaler.fit(fakeJpeg, "image/jpeg", MAX_SIDE);

        assertThat(fitted.data()).isSameAs(fakeJpeg);
    }

    @Test
    void aFormatWithoutAReaderIsReturnedUntouched() {
        byte[] webp = new byte[64];

        ImageDownscaler.Fitted fitted = ImageDownscaler.fit(webp, "image/webp", MAX_SIDE);

        assertThat(fitted.data()).isSameAs(webp);
        assertThat(fitted.mime()).isEqualTo("image/webp");
    }

    @Test
    void aNonPositiveLimitDisablesTheReduction() throws IOException {
        byte[] original = encode(drawing(2000, 1000, BufferedImage.TYPE_INT_RGB), "png");

        assertThat(ImageDownscaler.fit(original, "image/png", 0).data()).isSameAs(original);
    }

    private static BufferedImage drawing(int width, int height, int type) {
        BufferedImage image = new BufferedImage(width, height, type);
        Graphics2D graphics = image.createGraphics();
        graphics.setColor(Color.WHITE);
        graphics.fillRect(0, 0, width, height);
        graphics.setColor(Color.BLACK);
        for (int offset = 0; offset < Math.max(width, height); offset += 40) {
            graphics.drawLine(offset, 0, 0, offset);
        }
        graphics.dispose();
        return image;
    }

    private static byte[] encode(BufferedImage image, String format) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, format, out);
        return out.toByteArray();
    }

    private static BufferedImage decode(byte[] data) throws IOException {
        return ImageIO.read(new ByteArrayInputStream(data));
    }
}

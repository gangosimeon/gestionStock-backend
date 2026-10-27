package com.geststock.product;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.Test;

import com.geststock.exception.ErrorCode;
import com.geststock.exception.InvalidRequestException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Photo de produit : quelle que soit l'image envoyée, le serveur conserve un JPEG réduit, sans
 * métadonnées ; tout fichier qui n'est pas une image lisible est refusé.
 */
class ProductImageProcessorTest {

	@Test
	void aLargePhotoIsReducedToAJpegOf512PixelsAtMostKeepingItsProportions() throws IOException {
		byte[] photo = encode(image(3000, 2000, BufferedImage.TYPE_INT_RGB, Color.ORANGE), "jpeg");

		ProductImageProcessor.Processed result = ProductImageProcessor.process(photo);

		assertThat(result.width()).isEqualTo(512);
		assertThat(result.height()).isEqualTo(341);
		assertThat(result.data()).startsWith((byte) 0xFF, (byte) 0xD8, (byte) 0xFF);
		assertThat(result.data().length).isLessThan(60 * 1024).isLessThan(photo.length);
		assertThat(result.sha256()).matches("[0-9a-f]{64}");
		BufferedImage stored = ImageIO.read(new ByteArrayInputStream(result.data()));
		assertThat(stored.getWidth()).isEqualTo(512);
	}

	@Test
	void aSmallImageIsNeverEnlarged() throws IOException {
		ProductImageProcessor.Processed result = ProductImageProcessor
			.process(encode(image(120, 80, BufferedImage.TYPE_INT_RGB, Color.BLUE), "png"));

		assertThat(result.width()).isEqualTo(120);
		assertThat(result.height()).isEqualTo(80);
	}

	@Test
	void aTransparentPngIsPlacedOnAWhiteBackground() throws IOException {
		BufferedImage transparent = new BufferedImage(40, 40, BufferedImage.TYPE_INT_ARGB);

		ProductImageProcessor.Processed result = ProductImageProcessor.process(encode(transparent, "png"));

		Color pixel = new Color(ImageIO.read(new ByteArrayInputStream(result.data())).getRGB(20, 20));
		assertThat(pixel.getRed()).isGreaterThan(245);
		assertThat(pixel.getGreen()).isGreaterThan(245);
		assertThat(pixel.getBlue()).isGreaterThan(245);
	}

	@Test
	void theSameImageAlwaysGivesTheSameVersion() throws IOException {
		byte[] photo = encode(image(800, 600, BufferedImage.TYPE_INT_RGB, Color.GREEN), "png");

		assertThat(ProductImageProcessor.process(photo).sha256()).isEqualTo(ProductImageProcessor.process(photo).sha256());
	}

	@Test
	void anythingThatIsNotAReadableImageIsRefused() {
		assertRefused(new byte[0], ErrorCode.INVALID_PRODUCT_IMAGE);
		assertRefused("<svg onload=alert(1)>".getBytes(), ErrorCode.INVALID_PRODUCT_IMAGE);
		assertRefused("GIF89a......".getBytes(), ErrorCode.INVALID_PRODUCT_IMAGE);
		// Signature JPEG valide, contenu endommagé.
		assertRefused(new byte[] { (byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 0, 1, 2, 3, 4 }, ErrorCode.INVALID_PRODUCT_IMAGE);
	}

	@Test
	void oversizedFilesAndGiantImagesAreRefusedBeforeBeingDecoded() throws IOException {
		byte[] heavy = new byte[ProductImageProcessor.MAX_UPLOAD_BYTES + 1];
		heavy[0] = (byte) 0xFF;
		heavy[1] = (byte) 0xD8;
		heavy[2] = (byte) 0xFF;
		assertRefused(heavy, ErrorCode.FILE_TOO_LARGE);

		// Dimensions lues dans l'en-tête : refus sans décoder l'image entière.
		byte[] giant = encode(image(ProductImageProcessor.MAX_SOURCE_SIDE + 1, 2, BufferedImage.TYPE_INT_RGB, Color.RED), "png");
		assertThatThrownBy(() -> ProductImageProcessor.process(giant))
			.isInstanceOf(InvalidRequestException.class)
			.hasMessageContaining("trop grande");
	}

	private static void assertRefused(byte[] data, ErrorCode code) {
		assertThatThrownBy(() -> ProductImageProcessor.process(data))
			.isInstanceOfSatisfying(InvalidRequestException.class, ex -> assertThat(ex.getCode()).isEqualTo(code));
	}

	private static BufferedImage image(int width, int height, int type, Color color) {
		BufferedImage image = new BufferedImage(width, height, type);
		Graphics2D g = image.createGraphics();
		g.setColor(color);
		g.fillRect(0, 0, width, height);
		g.setColor(Color.BLACK);
		g.drawLine(0, 0, width - 1, height - 1);
		g.dispose();
		return image;
	}

	private static byte[] encode(BufferedImage image, String format) throws IOException {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		ImageIO.write(image, format, out);
		return out.toByteArray();
	}

}

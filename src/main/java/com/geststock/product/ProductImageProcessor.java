package com.geststock.product;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Iterator;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.ImageOutputStream;

import com.geststock.exception.ErrorCode;
import com.geststock.exception.InvalidRequestException;

/**
 * Photo de produit : vérifie l'image envoyée puis la <b>ré-encode toujours</b> en JPEG réduit.
 *
 * <ul>
 * <li>Type reconnu sur le contenu (signature PNG ou JPEG), jamais sur le nom du fichier.</li>
 * <li>Dimensions lues avant le décodage complet : une image géante (« bombe de décompression ») est
 * refusée sans être chargée en mémoire.</li>
 * <li>Réduction à {@value #MAX_SIDE} px sur le plus grand côté (jamais agrandie), fond blanc pour la
 * transparence, JPEG qualité {@value #QUALITY} : quelques dizaines de Ko, quelle que soit la photo.</li>
 * <li>Ré-encodage : métadonnées (EXIF, position GPS d'un téléphone) et contenu non image écartés.</li>
 * </ul>
 */
final class ProductImageProcessor {

	/** Taille maximale du fichier envoyé (photo de téléphone). */
	static final int MAX_UPLOAD_BYTES = 8 * 1024 * 1024;

	/** Dimensions maximales acceptées avant réduction. */
	static final int MAX_SOURCE_SIDE = 8000;

	/** Plus grand côté de l'image conservée. */
	static final int MAX_SIDE = 512;

	static final float QUALITY = 0.82f;

	static final String CONTENT_TYPE = "image/jpeg";

	private static final byte[] PNG_SIGNATURE = { (byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n' };

	private static final byte[] JPEG_SIGNATURE = { (byte) 0xFF, (byte) 0xD8, (byte) 0xFF };

	/** Image conservée : JPEG ré-encodé, dimensions et empreinte. */
	record Processed(byte[] data, int width, int height, String sha256) {
	}

	private ProductImageProcessor() {
	}

	static Processed process(byte[] source) {
		if (source == null || source.length == 0) {
			throw invalid("Le fichier est vide.");
		}
		if (source.length > MAX_UPLOAD_BYTES) {
			throw new InvalidRequestException(ErrorCode.FILE_TOO_LARGE, "Photo trop lourde : 8 Mo au plus.");
		}
		String format = startsWith(source, PNG_SIGNATURE) ? "png" : startsWith(source, JPEG_SIGNATURE) ? "jpeg" : null;
		if (format == null) {
			throw invalid("Format non reconnu : choisissez une photo JPEG ou PNG.");
		}
		BufferedImage decoded = decode(source, format);
		BufferedImage reduced = reduce(decoded);
		byte[] jpeg = encodeJpeg(reduced);
		return new Processed(jpeg, reduced.getWidth(), reduced.getHeight(), sha256(jpeg));
	}

	private static BufferedImage decode(byte[] source, String format) {
		try (ImageInputStream input = ImageIO.createImageInputStream(new ByteArrayInputStream(source))) {
			Iterator<ImageReader> readers = ImageIO.getImageReadersByFormatName(format);
			if (input == null || !readers.hasNext()) {
				throw invalid("Image illisible.");
			}
			ImageReader reader = readers.next();
			try {
				reader.setInput(input, true, true);
				int width = reader.getWidth(0);
				int height = reader.getHeight(0);
				if (width < 1 || height < 1 || width > MAX_SOURCE_SIDE || height > MAX_SOURCE_SIDE) {
					throw invalid("Image trop grande : " + MAX_SOURCE_SIDE + " × " + MAX_SOURCE_SIDE + " pixels au plus.");
				}
				BufferedImage image = reader.read(0);
				if (image == null) {
					throw invalid("Image illisible.");
				}
				return image;
			}
			finally {
				reader.dispose();
			}
		}
		catch (IOException | RuntimeException ex) {
			if (ex instanceof InvalidRequestException invalid) {
				throw invalid;
			}
			throw invalid("Image illisible ou endommagée.");
		}
	}

	/**
	 * Réduction par étapes successives (moitié par moitié puis taille finale) : rendu net, sans
	 * l'aspect crénelé d'une réduction directe d'une grande photo.
	 */
	static BufferedImage reduce(BufferedImage source) {
		int width = source.getWidth();
		int height = source.getHeight();
		double scale = Math.min(1.0, (double) MAX_SIDE / Math.max(width, height));
		int targetWidth = Math.max(1, (int) Math.round(width * scale));
		int targetHeight = Math.max(1, (int) Math.round(height * scale));

		BufferedImage current = flatten(source);
		while (current.getWidth() / 2 >= targetWidth && current.getHeight() / 2 >= targetHeight) {
			current = draw(current, current.getWidth() / 2, current.getHeight() / 2);
		}
		if (current.getWidth() != targetWidth || current.getHeight() != targetHeight) {
			current = draw(current, targetWidth, targetHeight);
		}
		return current;
	}

	/** Image RVB sur fond blanc (la transparence d'un PNG n'existe pas en JPEG). */
	private static BufferedImage flatten(BufferedImage source) {
		return draw(source, source.getWidth(), source.getHeight());
	}

	private static BufferedImage draw(BufferedImage source, int width, int height) {
		BufferedImage target = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
		Graphics2D g = target.createGraphics();
		try {
			g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
			g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
			g.setColor(Color.WHITE);
			g.fillRect(0, 0, width, height);
			g.drawImage(source, 0, 0, width, height, null);
		}
		finally {
			g.dispose();
		}
		return target;
	}

	private static byte[] encodeJpeg(BufferedImage image) {
		Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpeg");
		if (!writers.hasNext()) {
			throw new IllegalStateException("Encodeur JPEG indisponible");
		}
		ImageWriter writer = writers.next();
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		try (ImageOutputStream output = ImageIO.createImageOutputStream(bytes)) {
			writer.setOutput(output);
			ImageWriteParam param = writer.getDefaultWriteParam();
			param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
			param.setCompressionQuality(QUALITY);
			writer.write(null, new IIOImage(image, null, null), param);
		}
		catch (IOException ex) {
			throw new IllegalStateException("Encodage JPEG impossible", ex);
		}
		finally {
			writer.dispose();
		}
		return bytes.toByteArray();
	}

	private static boolean startsWith(byte[] data, byte[] prefix) {
		if (data.length < prefix.length) {
			return false;
		}
		for (int i = 0; i < prefix.length; i++) {
			if (data[i] != prefix[i]) {
				return false;
			}
		}
		return true;
	}

	private static String sha256(byte[] data) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
		}
		catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException(ex);
		}
	}

	private static InvalidRequestException invalid(String message) {
		return new InvalidRequestException(ErrorCode.INVALID_PRODUCT_IMAGE, message);
	}

}

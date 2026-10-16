package com.geststock.settings;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Iterator;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;

import com.geststock.exception.ErrorCode;
import com.geststock.exception.InvalidRequestException;

/**
 * Logo vérifié : le type réel est lu dans les premiers octets (signature PNG ou JPEG), jamais
 * déduit du nom du fichier ni du type déclaré par le navigateur. Les dimensions sont lues dans
 * l'en-tête avant tout décodage (une image de 100 000 × 100 000 pixels serait refusée sans être
 * chargée en mémoire), puis l'image est décodée entièrement pour écarter un fichier tronqué.
 */
record LogoImage(String contentType, byte[] data, int width, int height, String sha256) {

	static final int MAX_BYTES = 2 * 1024 * 1024;

	static final int MAX_DIMENSION = 4000;

	private static final byte[] PNG_SIGNATURE = { (byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n' };

	private static final byte[] JPEG_SIGNATURE = { (byte) 0xFF, (byte) 0xD8, (byte) 0xFF };

	static LogoImage of(byte[] data) {
		if (data == null || data.length == 0) {
			throw invalid("Le fichier est vide.");
		}
		if (data.length > MAX_BYTES) {
			throw new InvalidRequestException(ErrorCode.FILE_TOO_LARGE, ErrorCode.FILE_TOO_LARGE.defaultMessage());
		}
		String contentType = startsWith(data, PNG_SIGNATURE) ? "image/png"
				: startsWith(data, JPEG_SIGNATURE) ? "image/jpeg" : null;
		if (contentType == null) {
			throw invalid("Format non reconnu : choisissez une image PNG ou JPEG.");
		}
		String formatName = contentType.equals("image/png") ? "png" : "jpeg";

		try (ImageInputStream input = ImageIO.createImageInputStream(new ByteArrayInputStream(data))) {
			Iterator<ImageReader> readers = ImageIO.getImageReadersByFormatName(formatName);
			if (input == null || !readers.hasNext()) {
				throw invalid("Image illisible.");
			}
			ImageReader reader = readers.next();
			try {
				reader.setInput(input, true, true);
				int width = reader.getWidth(0);
				int height = reader.getHeight(0);
				if (width < 1 || height < 1 || width > MAX_DIMENSION || height > MAX_DIMENSION) {
					throw invalid("Image trop grande : " + MAX_DIMENSION + " × " + MAX_DIMENSION + " pixels maximum.");
				}
				BufferedImage image = reader.read(0);
				if (image == null) {
					throw invalid("Image illisible.");
				}
				return new LogoImage(contentType, data, width, height, sha256(data));
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
			throw new IllegalStateException("SHA-256 indisponible", ex);
		}
	}

	private static InvalidRequestException invalid(String detail) {
		return new InvalidRequestException(ErrorCode.INVALID_LOGO, detail);
	}

}

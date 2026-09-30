package com.fixmycity.storage;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import com.fixmycity.common.ApiException;

import org.springframework.web.multipart.MultipartFile;

/** A validated image upload. Its type comes from the file signature, never from the client's Content-Type. */
public record ImageFile(byte[] bytes, String contentType, String extension, String originalName) {

	public static final long MAX_BYTES = 5L * 1024 * 1024;

	private record Type(String contentType, String extension) {
	}

	private static final byte[] PNG = { (byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n' };

	private static final byte[] RIFF = "RIFF".getBytes(StandardCharsets.US_ASCII);

	private static final byte[] WEBP = "WEBP".getBytes(StandardCharsets.US_ASCII);

	public static ImageFile of(MultipartFile file) {
		if (file == null || file.isEmpty()) {
			throw ApiException.badRequest("The image file is empty.");
		}
		if (file.getSize() > MAX_BYTES) {
			throw ApiException.badRequest("Images must be 5 MB or smaller.");
		}
		byte[] bytes;
		try {
			bytes = file.getBytes();
		}
		catch (IOException ex) {
			throw ApiException.badRequest("The image could not be read.");
		}
		Type type = detect(bytes);
		if (type == null) {
			throw ApiException.badRequest("Only JPEG, PNG or WebP images are accepted.");
		}
		return new ImageFile(bytes, type.contentType(), type.extension(), displayName(file.getOriginalFilename()));
	}

	private static Type detect(byte[] b) {
		if (b.length >= 3 && (b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8 && (b[2] & 0xFF) == 0xFF) {
			return new Type("image/jpeg", "jpg");
		}
		if (startsWith(b, 0, PNG)) {
			return new Type("image/png", "png");
		}
		if (startsWith(b, 0, RIFF) && startsWith(b, 8, WEBP)) {
			return new Type("image/webp", "webp");
		}
		return null;
	}

	private static boolean startsWith(byte[] bytes, int offset, byte[] prefix) {
		return bytes.length >= offset + prefix.length
				&& Arrays.equals(bytes, offset, offset + prefix.length, prefix, 0, prefix.length);
	}

	/** File name for display only; storage keys never use client input. */
	private static String displayName(String name) {
		if (name == null) {
			return null;
		}
		String base = name.substring(Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\')) + 1)
			.replaceAll("\\p{Cntrl}", "")
			.strip();
		return base.isEmpty() ? null : base.substring(0, Math.min(base.length(), 255));
	}

}

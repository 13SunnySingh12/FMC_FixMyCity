package com.fixmycity.common;

public final class Text {

	private Text() {
	}

	/** Trims input and treats blank strings as absent. */
	public static String blankToNull(String value) {
		return (value == null || value.isBlank()) ? null : value.strip();
	}

}

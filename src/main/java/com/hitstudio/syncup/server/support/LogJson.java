package com.hitstudio.syncup.server.support;

import org.slf4j.MDC;

import java.lang.reflect.Array;
import java.util.Collection;
import java.util.Iterator;
import java.util.Map;

public final class LogJson {
	private LogJson() {
	}

	public static String event(String event, Object... fields) {
		StringBuilder builder = new StringBuilder();
		builder.append('{');
		appendField(builder, "event", event);
		appendMdcField(builder, "requestId", "requestId");
		appendMdcField(builder, "sessionId", "sessionId");
		if (fields.length % 2 != 0) {
			throw new IllegalArgumentException("fields must be key/value pairs");
		}
		for (int index = 0; index < fields.length; index += 2) {
			builder.append(',');
			appendField(builder, String.valueOf(fields[index]), fields[index + 1]);
		}
		builder.append('}');
		return builder.toString();
	}

	private static void appendMdcField(StringBuilder builder, String jsonKey, String mdcKey) {
		String value = MDC.get(mdcKey);
		if (value != null && !value.isBlank()) {
			builder.append(',');
			appendField(builder, jsonKey, value);
		}
	}

	private static void appendField(StringBuilder builder, String key, Object value) {
		builder.append('"').append(escape(key)).append('"').append(':');
		appendValue(builder, value);
	}

	private static void appendValue(StringBuilder builder, Object value) {
		if (value == null) {
			builder.append("null");
			return;
		}
		if (value instanceof Number || value instanceof Boolean) {
			builder.append(value);
			return;
		}
		if (value instanceof Map<?, ?> map) {
			builder.append('{');
			Iterator<? extends Map.Entry<?, ?>> entries = map.entrySet().iterator();
			boolean first = true;
			while (entries.hasNext()) {
				Map.Entry<?, ?> entry = entries.next();
				if (!first) {
					builder.append(',');
				}
				first = false;
				appendField(builder, String.valueOf(entry.getKey()), entry.getValue());
			}
			builder.append('}');
			return;
		}
		if (value instanceof Collection<?> collection) {
			builder.append('[');
			Iterator<?> iterator = collection.iterator();
			boolean first = true;
			while (iterator.hasNext()) {
				if (!first) {
					builder.append(',');
				}
				first = false;
				appendValue(builder, iterator.next());
			}
			builder.append(']');
			return;
		}
		Class<?> type = value.getClass();
		if (type.isArray()) {
			builder.append('[');
			int length = Array.getLength(value);
			for (int index = 0; index < length; index++) {
				if (index > 0) {
					builder.append(',');
				}
				appendValue(builder, Array.get(value, index));
			}
			builder.append(']');
			return;
		}
		builder.append('"').append(escape(String.valueOf(value))).append('"');
	}

	private static String escape(String value) {
		StringBuilder builder = new StringBuilder(value.length() + 16);
		for (int index = 0; index < value.length(); index++) {
			char ch = value.charAt(index);
			switch (ch) {
				case '\\' -> builder.append("\\\\");
				case '"' -> builder.append("\\\"");
				case '\b' -> builder.append("\\b");
				case '\f' -> builder.append("\\f");
				case '\n' -> builder.append("\\n");
				case '\r' -> builder.append("\\r");
				case '\t' -> builder.append("\\t");
				default -> {
					if (ch < 0x20) {
						builder.append(String.format("\\u%04x", (int) ch));
					} else {
						builder.append(ch);
					}
				}
			}
		}
		return builder.toString();
	}
}

package com.torrent;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class BencodeParser {
    private final ByteArrayInputStream input;

    public BencodeParser(byte[] data) {
        this.input = new ByteArrayInputStream(data);
    }

    public Object parse() throws IOException {
        return parseNext();
    }

    private Object parseNext() throws IOException {
        int nextByte = input.read();
        if (nextByte == -1) {
            throw new IOException("Unexpected end of input");
        }

        switch (nextByte) {
            case 'i':
                return parseInteger();
            case 'l':
                return parseList();
            case 'd':
                return parseDictionary();
            default:
                input.reset();
                return parseString();
        }
    }

    private long parseInteger() throws IOException {
        StringBuilder sb = new StringBuilder();
        int nextByte;
        while ((nextByte = input.read()) != 'e') {
            if (nextByte == -1) {
                throw new IOException("Unexpected end of integer");
            }
            sb.append((char) nextByte);
        }
        return Long.parseLong(sb.toString());
    }

    private String parseString() throws IOException {
        StringBuilder lengthStr = new StringBuilder();
        int nextByte;
        while ((nextByte = input.read()) != ':') {
            if (nextByte == -1) {
                throw new IOException("Unexpected end of string length");
            }
            lengthStr.append((char) nextByte);
        }

        int length = Integer.parseInt(lengthStr.toString());
        byte[] bytes = new byte[length];
        int read = input.read(bytes);
        if (read != length) {
            throw new IOException("Unexpected end of string");
        }

        return new String(bytes, StandardCharsets.ISO_8859_1);
    }

    private List<Object> parseList() throws IOException {
        List<Object> list = new ArrayList<>();
        while (true) {
            input.mark(1);
            int nextByte = input.read();
            if (nextByte == 'e') {
                break;
            }
            input.reset();
            list.add(parseNext());
        }
        return list;
    }

    private Map<String, Object> parseDictionary() throws IOException {
        Map<String, Object> dict = new HashMap<>();
        while (true) {
            input.mark(1);
            int nextByte = input.read();
            if (nextByte == 'e') {
                break;
            }
            input.reset();
            String key = parseString();
            Object value = parseNext();
            dict.put(key, value);
        }
        return dict;
    }

    public static byte[] encode(Object obj) {
        ByteBuffer buffer = ByteBuffer.allocate(1024);
        encode(obj, buffer);
        buffer.flip();
        byte[] result = new byte[buffer.remaining()];
        buffer.get(result);
        return result;
    }

    private static void encode(Object obj, ByteBuffer buffer) {
        if (obj instanceof Long) {
            encodeInteger((Long) obj, buffer);
        } else if (obj instanceof String) {
            encodeString((String) obj, buffer);
        } else if (obj instanceof List) {
            encodeList((List<?>) obj, buffer);
        } else if (obj instanceof Map) {
            encodeDictionary((Map<?, ?>) obj, buffer);
        }
    }

    private static void encodeInteger(Long value, ByteBuffer buffer) {
        buffer.put((byte) 'i');
        buffer.put(value.toString().getBytes(StandardCharsets.ISO_8859_1));
        buffer.put((byte) 'e');
    }

    private static void encodeString(String value, ByteBuffer buffer) {
        byte[] bytes = value.getBytes(StandardCharsets.ISO_8859_1);
        buffer.put(Integer.toString(bytes.length).getBytes(StandardCharsets.ISO_8859_1));
        buffer.put((byte) ':');
        buffer.put(bytes);
    }

    private static void encodeList(List<?> list, ByteBuffer buffer) {
        buffer.put((byte) 'l');
        for (Object item : list) {
            encode(item, buffer);
        }
        buffer.put((byte) 'e');
    }

    private static void encodeDictionary(Map<?, ?> dict, ByteBuffer buffer) {
        buffer.put((byte) 'd');
        for (Map.Entry<?, ?> entry : dict.entrySet()) {
            encodeString(entry.getKey().toString(), buffer);
            encode(entry.getValue(), buffer);
        }
        buffer.put((byte) 'e');
    }
} 
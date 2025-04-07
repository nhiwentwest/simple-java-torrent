package com.torrent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.util.BitSet;
import java.util.function.BiConsumer;
import java.util.function.Supplier;

public class PeerConnection implements Runnable {
    private static final Logger logger = LoggerFactory.getLogger(PeerConnection.class);
    private static final int HANDSHAKE_LENGTH = 68;
    private static final String PROTOCOL_IDENTIFIER = "BitTorrent protocol";
    private static final int BLOCK_SIZE = 16384;

    private final String ip;
    private final int port;
    private final byte[] infoHash;
    private final String peerId;
    private final BitSet pieces;
    private final BiConsumer<Integer, byte[]> pieceCompleteCallback;
    private final Supplier<Block> blockRequestCallback;
    private SocketChannel socket;
    private ByteBuffer buffer;
    private boolean running;
    private boolean choked = true;
    private boolean interested = false;

    public PeerConnection(String ip, int port, byte[] infoHash, String peerId, BitSet pieces,
                         BiConsumer<Integer, byte[]> pieceCompleteCallback, Supplier<Block> blockRequestCallback) {
        this.ip = ip;
        this.port = port;
        this.infoHash = infoHash;
        this.peerId = peerId;
        this.pieces = pieces;
        this.pieceCompleteCallback = pieceCompleteCallback;
        this.blockRequestCallback = blockRequestCallback;
    }

    @Override
    public void run() {
        try {
            connect();
            performHandshake();
            startMessageLoop();
        } catch (IOException e) {
            logger.error("Error in peer connection", e);
        } finally {
            disconnect();
        }
    }

    private void connect() throws IOException {
        socket = SocketChannel.open();
        socket.connect(new InetSocketAddress(ip, port));
        socket.configureBlocking(false);
        buffer = ByteBuffer.allocate(16384);
    }

    private void performHandshake() throws IOException {
        ByteBuffer handshake = ByteBuffer.allocate(HANDSHAKE_LENGTH);
        handshake.put((byte) PROTOCOL_IDENTIFIER.length());
        handshake.put(PROTOCOL_IDENTIFIER.getBytes());
        handshake.put(new byte[8]); // Reserved bytes
        handshake.put(infoHash);
        handshake.put(peerId.getBytes());
        handshake.flip();

        while (handshake.hasRemaining()) {
            socket.write(handshake);
        }

        ByteBuffer response = ByteBuffer.allocate(HANDSHAKE_LENGTH);
        while (response.position() < HANDSHAKE_LENGTH) {
            int read = socket.read(response);
            if (read == -1) {
                throw new IOException("Connection closed during handshake");
            }
        }
        response.flip();

        // Verify handshake response
        byte protocolLength = response.get();
        byte[] protocol = new byte[protocolLength];
        response.get(protocol);
        if (!new String(protocol).equals(PROTOCOL_IDENTIFIER)) {
            throw new IOException("Invalid protocol identifier");
        }

        response.position(response.position() + 8); // Skip reserved bytes
        byte[] peerInfoHash = new byte[20];
        response.get(peerInfoHash);
        if (!java.util.Arrays.equals(peerInfoHash, infoHash)) {
            throw new IOException("Info hash mismatch");
        }
    }

    private void startMessageLoop() throws IOException {
        running = true;
        while (running) {
            if (socket.read(buffer) == -1) {
                break;
            }
            buffer.flip();
            while (buffer.remaining() >= 4) {
                int length = buffer.getInt();
                if (length == 0) {
                    // Keep-alive message
                    continue;
                }
                if (buffer.remaining() < length) {
                    buffer.position(buffer.position() - 4);
                    break;
                }
                byte messageId = buffer.get();
                handleMessage(messageId, length - 1);
            }
            buffer.compact();

            if (!choked && interested) {
                requestBlock();
            }
        }
    }

    private void handleMessage(byte messageId, int length) {
        switch (messageId) {
            case 0: // choke
                handleChoke();
                break;
            case 1: // unchoke
                handleUnchoke();
                break;
            case 2: // interested
                handleInterested();
                break;
            case 3: // not interested
                handleNotInterested();
                break;
            case 4: // have
                handleHave(buffer.getInt());
                break;
            case 5: // bitfield
                handleBitfield(length);
                break;
            case 6: // request
                handleRequest(buffer.getInt(), buffer.getInt(), buffer.getInt());
                break;
            case 7: // piece
                handlePiece(buffer.getInt(), buffer.getInt(), length - 8);
                break;
            case 8: // cancel
                handleCancel(buffer.getInt(), buffer.getInt(), buffer.getInt());
                break;
            default:
                logger.warn("Unknown message ID: {}", messageId);
                buffer.position(buffer.position() + length);
        }
    }

    private void handleChoke() {
        choked = true;
    }

    private void handleUnchoke() {
        choked = false;
    }

    private void handleInterested() {
        interested = true;
    }

    private void handleNotInterested() {
        interested = false;
    }

    private void handleHave(int pieceIndex) {
        pieces.set(pieceIndex);
    }

    private void handleBitfield(int length) {
        byte[] bitfield = new byte[length];
        buffer.get(bitfield);
        for (int i = 0; i < bitfield.length; i++) {
            for (int j = 0; j < 8; j++) {
                if ((bitfield[i] & (1 << (7 - j))) != 0) {
                    pieces.set(i * 8 + j);
                }
            }
        }
    }

    private void handleRequest(int index, int begin, int length) {
        // TODO: Implement request handling
    }

    private void handlePiece(int index, int begin, int length) {
        byte[] data = new byte[length];
        buffer.get(data);
        pieceCompleteCallback.accept(index, data);
    }

    private void handleCancel(int index, int begin, int length) {
        // TODO: Implement cancel handling
    }

    private void requestBlock() {
        Block block = blockRequestCallback.get();
        if (block != null) {
            ByteBuffer request = ByteBuffer.allocate(17);
            request.putInt(13);
            request.put((byte) 6);
            request.putInt(block.getIndex());
            request.putInt(0);
            request.putInt((int) block.getSize());
            request.flip();
            try {
                socket.write(request);
            } catch (IOException e) {
                logger.error("Error sending request", e);
            }
        }
    }

    public void disconnect() {
        running = false;
        try {
            if (socket != null) {
                socket.close();
            }
        } catch (IOException e) {
            logger.error("Error closing socket", e);
        }
    }

    public static class Block {
        private final int index;
        private final long size;
        private boolean downloaded;

        public Block(int index, long size) {
            this.index = index;
            this.size = size;
            this.downloaded = false;
        }

        public int getIndex() {
            return index;
        }

        public long getSize() {
            return size;
        }

        public boolean isDownloaded() {
            return downloaded;
        }

        public void setDownloaded(boolean downloaded) {
            this.downloaded = downloaded;
        }
    }
} 
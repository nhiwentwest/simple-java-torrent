package com.torrent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class TrackerServer {
    private static final Logger logger = LoggerFactory.getLogger(TrackerServer.class);
    private static final int PORT = 6969;
    private static final int BUFFER_SIZE = 8192;

    // Map<infoHash, List<PeerInfo>> - Lưu danh sách peer cho mỗi file
    private final Map<String, List<PeerInfo>> filePeers = new ConcurrentHashMap<>();
    // Map<infoHash, FileInfo> - Lưu thông tin file
    private final Map<String, FileInfo> fileInfo = new ConcurrentHashMap<>();
    private ServerSocketChannel serverSocket;
    private boolean running;

    public static void main(String[] args) {
        TrackerServer server = new TrackerServer();
        try {
            server.start();
        } catch (IOException e) {
            logger.error("Error starting tracker server", e);
        }
    }

    public void start() throws IOException {
        serverSocket = ServerSocketChannel.open();
        serverSocket.bind(new InetSocketAddress(PORT));
        serverSocket.configureBlocking(false);
        running = true;

        logger.info("Tracker server started on port {}", PORT);

        while (running) {
            try {
                SocketChannel clientSocket = serverSocket.accept();
                if (clientSocket != null) {
                    handleClient(clientSocket);
                }
            } catch (IOException e) {
                logger.error("Error accepting client connection", e);
            }
        }
    }

    private void handleClient(SocketChannel clientSocket) {
        try {
            ByteBuffer buffer = ByteBuffer.allocate(BUFFER_SIZE);
            int bytesRead = clientSocket.read(buffer);
            if (bytesRead > 0) {
                buffer.flip();
                byte[] data = new byte[buffer.remaining()];
                buffer.get(data);
                handleRequest(data, clientSocket);
            }
        } catch (IOException e) {
            logger.error("Error handling client request", e);
        }
    }

    private void handleRequest(byte[] data, SocketChannel clientSocket) {
        try {
            BencodeParser parser = new BencodeParser(data);
            Map<String, Object> request = (Map<String, Object>) parser.parse();

            String action = (String) request.get("action");
            switch (action) {
                case "upload":
                    handleUpload(request, clientSocket);
                    break;
                case "list":
                    handleList(clientSocket);
                    break;
                case "announce":
                    handleAnnounce(request, clientSocket);
                    break;
                default:
                    logger.warn("Unknown action: {}", action);
            }
        } catch (Exception e) {
            logger.error("Error parsing request", e);
        }
    }

    private void handleUpload(Map<String, Object> request, SocketChannel clientSocket) {
        try {
            String fileName = (String) request.get("file_name");
            String infoHash = (String) request.get("info_hash");
            long fileSize = ((Number) request.get("file_size")).longValue();
            String peerId = (String) request.get("peer_id");
            int port = ((Number) request.get("port")).intValue();

            // Lưu thông tin file
            FileInfo file = new FileInfo(fileName, infoHash, fileSize);
            fileInfo.put(infoHash, file);

            // Thêm peer vào danh sách
            PeerInfo peer = new PeerInfo(peerId, infoHash, port, 0, 0, fileSize);
            filePeers.computeIfAbsent(infoHash, k -> new ArrayList<>()).add(peer);

            // Gửi phản hồi thành công
            Map<String, Object> response = Map.of("status", "success");
            sendResponse(clientSocket, response);

            logger.info("File uploaded: {} ({} bytes)", fileName, fileSize);
        } catch (Exception e) {
            logger.error("Error handling upload", e);
            sendError(clientSocket, "Upload failed");
        }
    }

    private void handleList(SocketChannel clientSocket) {
        try {
            List<Map<String, Object>> files = new ArrayList<>();
            for (FileInfo file : fileInfo.values()) {
                files.add(Map.of(
                    "file_name", file.getFileName(),
                    "info_hash", file.getInfoHash(),
                    "file_size", file.getFileSize(),
                    "peers", filePeers.getOrDefault(file.getInfoHash(), Collections.emptyList()).size()
                ));
            }
            Map<String, Object> response = Map.of("files", files);
            sendResponse(clientSocket, response);
        } catch (Exception e) {
            logger.error("Error handling list", e);
            sendError(clientSocket, "List failed");
        }
    }

    private void handleAnnounce(Map<String, Object> request, SocketChannel clientSocket) {
        try {
            String infoHash = (String) request.get("info_hash");
            String peerId = (String) request.get("peer_id");
            int port = ((Number) request.get("port")).intValue();
            long uploaded = ((Number) request.get("uploaded")).longValue();
            long downloaded = ((Number) request.get("downloaded")).longValue();
            long left = ((Number) request.get("left")).longValue();

            // Cập nhật thông tin peer
            PeerInfo peer = new PeerInfo(peerId, infoHash, port, uploaded, downloaded, left);
            List<PeerInfo> peers = filePeers.computeIfAbsent(infoHash, k -> new ArrayList<>());
            peers.removeIf(p -> p.getPeerId().equals(peerId));
            peers.add(peer);

            // Gửi danh sách peer
            Map<String, Object> response = Map.of(
                "interval", 1800,
                "peers", peers
            );
            sendResponse(clientSocket, response);
        } catch (Exception e) {
            logger.error("Error handling announce", e);
            sendError(clientSocket, "Announce failed");
        }
    }

    private void sendResponse(SocketChannel clientSocket, Map<String, Object> response) {
        try {
            byte[] responseData = BencodeParser.encode(response);
            ByteBuffer buffer = ByteBuffer.wrap(responseData);
            clientSocket.write(buffer);
        } catch (IOException e) {
            logger.error("Error sending response", e);
        }
    }

    private void sendError(SocketChannel clientSocket, String message) {
        try {
            Map<String, Object> error = Map.of("error", message);
            byte[] errorData = BencodeParser.encode(error);
            ByteBuffer buffer = ByteBuffer.wrap(errorData);
            clientSocket.write(buffer);
        } catch (IOException e) {
            logger.error("Error sending error response", e);
        }
    }

    public void stop() {
        running = false;
        try {
            if (serverSocket != null) {
                serverSocket.close();
            }
        } catch (IOException e) {
            logger.error("Error stopping server", e);
        }
    }

    private static class FileInfo {
        private final String fileName;
        private final String infoHash;
        private final long fileSize;

        public FileInfo(String fileName, String infoHash, long fileSize) {
            this.fileName = fileName;
            this.infoHash = infoHash;
            this.fileSize = fileSize;
        }

        public String getFileName() {
            return fileName;
        }

        public String getInfoHash() {
            return infoHash;
        }

        public long getFileSize() {
            return fileSize;
        }
    }

    private static class PeerInfo {
        private final String peerId;
        private final String infoHash;
        private final int port;
        private final long uploaded;
        private final long downloaded;
        private final long left;

        public PeerInfo(String peerId, String infoHash, int port, long uploaded, long downloaded, long left) {
            this.peerId = peerId;
            this.infoHash = infoHash;
            this.port = port;
            this.uploaded = uploaded;
            this.downloaded = downloaded;
            this.left = left;
        }

        public String getPeerId() {
            return peerId;
        }

        public String getInfoHash() {
            return infoHash;
        }

        public int getPort() {
            return port;
        }

        public long getUploaded() {
            return uploaded;
        }

        public long getDownloaded() {
            return downloaded;
        }

        public long getLeft() {
            return left;
        }
    }
} 
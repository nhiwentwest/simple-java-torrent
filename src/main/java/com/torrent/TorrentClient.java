package com.torrent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.URL;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;
import java.util.function.Supplier;

public class TorrentClient {
    private static final Logger logger = LoggerFactory.getLogger(TorrentClient.class);
    private static final int BLOCK_SIZE = 16384;
    private static final int PIECE_SIZE = 262144;
    private static final int MAX_PEERS = 50;
    private static final int CONNECT_TIMEOUT = 5000;
    private static final String PEER_ID = "-JT0001-0123456789AB";
    private static final int MAX_RETRIES = 3;
    private static final int MAX_CONCURRENT_DOWNLOADS = 5;
    private static final int MAX_CONCURRENT_UPLOADS = 5;

    private final TorrentFile torrentFile;
    private final File downloadDir;
    private final Map<String, PeerConnection> peerConnections = new ConcurrentHashMap<>();
    private final Map<Integer, byte[]> pieceData = new ConcurrentHashMap<>();
    private final Map<Integer, Boolean> pieceStatus = new ConcurrentHashMap<>();
    private final Map<Integer, List<Block>> pieceBlocks = new ConcurrentHashMap<>();
    private final Map<Integer, Integer> pieceRetries = new ConcurrentHashMap<>();
    private final BitSet pieces;
    private final byte[] infoHash;
    private final ExecutorService downloadExecutor;
    private final ExecutorService uploadExecutor;
    private final ServerSocket uploadSocket;
    private final AtomicLong downloadedBytes = new AtomicLong(0);
    private final AtomicLong uploadedBytes = new AtomicLong(0);
    private final Object pieceLock = new Object();
    private final AtomicInteger activeDownloads = new AtomicInteger(0);
    private final AtomicInteger activeUploads = new AtomicInteger(0);

    public TorrentClient(TorrentFile torrentFile, File downloadDir, byte[] infoHash) throws IOException {
        this.torrentFile = torrentFile;
        this.downloadDir = downloadDir;
        this.infoHash = infoHash;
        this.pieces = new BitSet();
        this.downloadExecutor = Executors.newFixedThreadPool(MAX_CONCURRENT_DOWNLOADS);
        this.uploadExecutor = Executors.newFixedThreadPool(MAX_CONCURRENT_UPLOADS);
        this.uploadSocket = new ServerSocket(0); // Random port
        initializePieceBlocks();
    }

    private void initializePieceBlocks() {
        int totalPieces = (int) Math.ceil((double) calculateTotalSize() / PIECE_SIZE);
        for (int i = 0; i < totalPieces; i++) {
            long pieceSize = Math.min(PIECE_SIZE, calculateTotalSize() - (long) i * PIECE_SIZE);
            int numBlocks = (int) Math.ceil((double) pieceSize / BLOCK_SIZE);
            List<Block> blocks = new ArrayList<>();
            for (int j = 0; j < numBlocks; j++) {
                long blockSize = Math.min(BLOCK_SIZE, pieceSize - (long) j * BLOCK_SIZE);
                blocks.add(new Block(j, blockSize));
            }
            pieceBlocks.put(i, blocks);
            pieceStatus.put(i, false);
            pieceRetries.put(i, 0);
        }
    }

    public void start() {
        logger.info("Starting torrent client for: {}", torrentFile.getInfo().getName());
        try {
            initializeFiles();
            loadProgress();
            startUploadServer();
            connectToTracker();
            startPeerConnections();
        } catch (Exception e) {
            logger.error("Error starting torrent client", e);
        }
    }

    private void startUploadServer() {
        new Thread(() -> {
            while (!uploadSocket.isClosed()) {
                try {
                    Socket clientSocket = uploadSocket.accept();
                    if (activeUploads.get() < MAX_CONCURRENT_UPLOADS) {
                        activeUploads.incrementAndGet();
                        uploadExecutor.submit(() -> handleUploadConnection(clientSocket));
                    } else {
                        clientSocket.close();
                    }
                } catch (IOException e) {
                    logger.error("Error accepting upload connection", e);
                }
            }
        }).start();
    }

    private void handleUploadConnection(Socket clientSocket) {
        try {
            // Handle upload request and serve blocks
            // Implementation depends on your protocol
        } catch (Exception e) {
            logger.error("Error handling upload connection", e);
        } finally {
            activeUploads.decrementAndGet();
            try {
                clientSocket.close();
            } catch (IOException e) {
                logger.error("Error closing upload socket", e);
            }
        }
    }

    private void connectToTracker() {
        try {
            String announceUrl = torrentFile.getAnnounce();
            String query = buildTrackerQuery();
            URL trackerUrl = new URL(announceUrl + "?" + query);
            
            HttpClient client = HttpClient.newHttpClient();
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(trackerUrl.toURI())
                    .GET()
                    .build();

            HttpResponse<byte[]> response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() == 200) {
                BencodeParser parser = new BencodeParser(response.body());
                Map<String, Object> dict = (Map<String, Object>) parser.parse();
                TrackerResponse trackerResponse = TrackerResponse.fromBencode(dict);
                handleTrackerResponse(trackerResponse);
            } else {
                logger.error("Tracker request failed with status: {}", response.statusCode());
            }
        } catch (Exception e) {
            logger.error("Error connecting to tracker", e);
        }
    }

    private void handleTrackerResponse(TrackerResponse response) {
        logger.info("Received {} peers from tracker", response.getPeers().size());
        for (TrackerResponse.PeerInfo peer : response.getPeers()) {
            if (peerConnections.size() >= MAX_PEERS) {
                break;
            }
            String peerKey = peer.getIp() + ":" + peer.getPort();
            if (!peerConnections.containsKey(peerKey)) {
                PeerConnection connection = new PeerConnection(
                    peer.getIp(),
                    peer.getPort(),
                    infoHash,
                    PEER_ID,
                    pieces,
                    this::handlePieceComplete,
                    this::getNextBlock
                );
                peerConnections.put(peerKey, connection);
                if (activeDownloads.get() < MAX_CONCURRENT_DOWNLOADS) {
                    activeDownloads.incrementAndGet();
                    downloadExecutor.submit(() -> {
                        try {
                            connection.run();
                        } finally {
                            activeDownloads.decrementAndGet();
                        }
                    });
                }
            }
        }
    }

    private void handlePieceComplete(int pieceIndex, byte[] data) {
        synchronized (pieceLock) {
            if (pieceStatus.get(pieceIndex)) {
                return;
            }

            try {
                if (verifyPiece(pieceIndex, data)) {
                    pieceData.put(pieceIndex, data);
                    pieceStatus.put(pieceIndex, true);
                    pieces.set(pieceIndex);
                    downloadedBytes.addAndGet(data.length);
                    savePieceToFile(pieceIndex, data);
                    saveProgress();
                    logger.info("Piece {} completed", pieceIndex);
                } else {
                    logger.warn("Piece {} verification failed", pieceIndex);
                    int retries = pieceRetries.get(pieceIndex);
                    if (retries < MAX_RETRIES) {
                        pieceRetries.put(pieceIndex, retries + 1);
                        pieceStatus.put(pieceIndex, false);
                    } else {
                        logger.error("Piece {} failed after {} retries", pieceIndex, MAX_RETRIES);
                    }
                }
            } catch (Exception e) {
                logger.error("Error handling piece completion", e);
            }
        }
    }

    private boolean verifyPiece(int pieceIndex, byte[] data) throws NoSuchAlgorithmException {
        MessageDigest digest = MessageDigest.getInstance("SHA-1");
        byte[] hash = digest.digest(data);
        String pieceHash = torrentFile.getInfo().getPieces().substring(pieceIndex * 40, (pieceIndex + 1) * 40);
        return java.util.Arrays.equals(hash, pieceHash.getBytes(StandardCharsets.ISO_8859_1));
    }

    private void savePieceToFile(int pieceIndex, byte[] data) throws IOException {
        if (torrentFile.getInfo().getFiles() != null) {
            long offset = (long) pieceIndex * PIECE_SIZE;
            for (TorrentFile.Info.FileInfo file : torrentFile.getInfo().getFiles()) {
                if (offset >= file.getLength()) {
                    offset -= file.getLength();
                    continue;
                }
                File targetFile = new File(downloadDir, String.join(File.separator, file.getPath()));
                long fileOffset = Math.max(0, offset);
                long pieceOffset = Math.max(0, -offset);
                long length = Math.min(data.length - pieceOffset, file.getLength() - fileOffset);
                Files.write(targetFile.toPath(), 
                    java.util.Arrays.copyOfRange(data, (int) pieceOffset, (int) (pieceOffset + length)),
                    StandardOpenOption.WRITE);
                offset += length;
            }
        } else {
            File targetFile = new File(downloadDir, torrentFile.getInfo().getName());
            Files.write(targetFile.toPath(), data, StandardOpenOption.WRITE);
        }
    }

    private PeerConnection.Block getNextBlock() {
        synchronized (pieceLock) {
            for (int i = 0; i < pieces.length(); i++) {
                if (!pieceStatus.get(i)) {
                    List<Block> blocks = pieceBlocks.get(i);
                    for (Block block : blocks) {
                        if (!block.isDownloaded()) {
                            return new PeerConnection.Block(block.getIndex(), block.getSize());
                        }
                    }
                }
            }
            return null;
        }
    }

    private String buildTrackerQuery() throws IOException {
        StringBuilder query = new StringBuilder();
        query.append("info_hash=").append(URLEncoder.encode(new String(infoHash, StandardCharsets.ISO_8859_1), StandardCharsets.ISO_8859_1));
        query.append("&peer_id=").append(PEER_ID);
        query.append("&port=").append(uploadSocket.getLocalPort());
        query.append("&uploaded=").append(uploadedBytes.get());
        query.append("&downloaded=").append(downloadedBytes.get());
        query.append("&left=").append(calculateTotalSize() - downloadedBytes.get());
        query.append("&compact=").append(1);
        return query.toString();
    }

    private long calculateTotalSize() {
        long totalSize = 0;
        if (torrentFile.getInfo().getFiles() != null) {
            for (TorrentFile.Info.FileInfo file : torrentFile.getInfo().getFiles()) {
                totalSize += file.getLength();
            }
        } else {
            totalSize = torrentFile.getInfo().getPieceLength();
        }
        return totalSize;
    }

    private void startPeerConnections() {
        // Peer connections are started in handleTrackerResponse
    }

    public void shutdown() {
        logger.info("Shutting down torrent client");
        try {
            downloadExecutor.shutdown();
            uploadExecutor.shutdown();
            uploadSocket.close();
            if (!downloadExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                downloadExecutor.shutdownNow();
            }
            if (!uploadExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                uploadExecutor.shutdownNow();
            }
        } catch (InterruptedException | IOException e) {
            logger.error("Error shutting down", e);
        }
    }

    private void initializeFiles() throws IOException {
        if (!downloadDir.exists()) {
            downloadDir.mkdirs();
        }

        if (torrentFile.getInfo().getFiles() != null) {
            for (TorrentFile.Info.FileInfo file : torrentFile.getInfo().getFiles()) {
                File targetFile = new File(downloadDir, String.join(File.separator, file.getPath()));
                if (!targetFile.getParentFile().exists()) {
                    targetFile.getParentFile().mkdirs();
                }
                if (!targetFile.exists()) {
                    targetFile.createNewFile();
                }
            }
        } else {
            File targetFile = new File(downloadDir, torrentFile.getInfo().getName());
            if (!targetFile.exists()) {
                targetFile.createNewFile();
            }
        }
    }

    private void loadProgress() throws IOException {
        File progressFile = new File(downloadDir, ".progress");
        if (progressFile.exists()) {
            byte[] data = Files.readAllBytes(progressFile.toPath());
            for (int i = 0; i < data.length; i++) {
                if (data[i] == 1) {
                    pieces.set(i);
                    pieceStatus.put(i, true);
                }
            }
        }
    }

    private void saveProgress() throws IOException {
        File progressFile = new File(downloadDir, ".progress");
        byte[] data = new byte[pieces.length()];
        for (int i = 0; i < pieces.length(); i++) {
            data[i] = (byte) (pieces.get(i) ? 1 : 0);
        }
        Files.write(progressFile.toPath(), data);
    }

    public static void main(String[] args) {
        if (args.length != 2) {
            System.out.println("Usage: java TorrentClient <torrent_file> <download_directory>");
            return;
        }

        File torrentFile = new File(args[0]);
        File downloadDir = new File(args[1]);

        if (!torrentFile.exists()) {
            System.out.println("Torrent file does not exist: " + torrentFile.getAbsolutePath());
            return;
        }

        if (!downloadDir.exists()) {
            downloadDir.mkdirs();
        }

        try {
            TorrentFile parsedTorrent = TorrentFileParser.parse(torrentFile);
            byte[] infoHash = TorrentFileParser.calculateInfoHash(torrentFile);
            TorrentClient client = new TorrentClient(parsedTorrent, downloadDir, infoHash);
            
            Runtime.getRuntime().addShutdownHook(new Thread(client::shutdown));
            client.start();
        } catch (Exception e) {
            logger.error("Error starting torrent client", e);
        }
    }

    private static class Block {
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
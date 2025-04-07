package com.torrent;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class TrackerResponse {
    private int interval;
    private int minInterval;
    private String trackerId;
    private int complete;
    private int incomplete;
    private List<PeerInfo> peers;

    public static class PeerInfo {
        private String ip;
        private int port;
        private String peerId;

        public String getIp() {
            return ip;
        }

        public void setIp(String ip) {
            this.ip = ip;
        }

        public int getPort() {
            return port;
        }

        public void setPort(int port) {
            this.port = port;
        }

        public String getPeerId() {
            return peerId;
        }

        public void setPeerId(String peerId) {
            this.peerId = peerId;
        }
    }

    public static TrackerResponse fromBencode(Map<String, Object> dict) {
        TrackerResponse response = new TrackerResponse();
        response.setInterval(((Long) dict.get("interval")).intValue());
        if (dict.containsKey("min interval")) {
            response.setMinInterval(((Long) dict.get("min interval")).intValue());
        }
        if (dict.containsKey("tracker id")) {
            response.setTrackerId((String) dict.get("tracker id"));
        }
        response.setComplete(((Long) dict.get("complete")).intValue());
        response.setIncomplete(((Long) dict.get("incomplete")).intValue());
        
        // Parse peers
        Object peersObj = dict.get("peers");
        if (peersObj instanceof String) {
            // Compact format
            response.setPeers(parseCompactPeers((String) peersObj));
        } else if (peersObj instanceof List) {
            // Non-compact format
            response.setPeers(parseNonCompactPeers((List<Map<String, Object>>) peersObj));
        }
        
        return response;
    }

    private static List<PeerInfo> parseCompactPeers(String peers) {
        List<PeerInfo> result = new ArrayList<>();
        byte[] bytes = peers.getBytes();
        for (int i = 0; i < bytes.length; i += 6) {
            PeerInfo peer = new PeerInfo();
            peer.setIp(String.format("%d.%d.%d.%d", 
                bytes[i] & 0xFF, 
                bytes[i + 1] & 0xFF, 
                bytes[i + 2] & 0xFF, 
                bytes[i + 3] & 0xFF));
            peer.setPort(((bytes[i + 4] & 0xFF) << 8) | (bytes[i + 5] & 0xFF));
            result.add(peer);
        }
        return result;
    }

    private static List<PeerInfo> parseNonCompactPeers(List<Map<String, Object>> peers) {
        List<PeerInfo> result = new ArrayList<>();
        for (Map<String, Object> peer : peers) {
            PeerInfo peerInfo = new PeerInfo();
            peerInfo.setIp((String) peer.get("ip"));
            peerInfo.setPort(((Long) peer.get("port")).intValue());
            if (peer.containsKey("peer id")) {
                peerInfo.setPeerId((String) peer.get("peer id"));
            }
            result.add(peerInfo);
        }
        return result;
    }

    public int getInterval() {
        return interval;
    }

    public void setInterval(int interval) {
        this.interval = interval;
    }

    public int getMinInterval() {
        return minInterval;
    }

    public void setMinInterval(int minInterval) {
        this.minInterval = minInterval;
    }

    public String getTrackerId() {
        return trackerId;
    }

    public void setTrackerId(String trackerId) {
        this.trackerId = trackerId;
    }

    public int getComplete() {
        return complete;
    }

    public void setComplete(int complete) {
        this.complete = complete;
    }

    public int getIncomplete() {
        return incomplete;
    }

    public void setIncomplete(int incomplete) {
        this.incomplete = incomplete;
    }

    public List<PeerInfo> getPeers() {
        return peers;
    }

    public void setPeers(List<PeerInfo> peers) {
        this.peers = peers;
    }
} 
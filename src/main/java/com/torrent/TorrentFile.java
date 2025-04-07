package com.torrent;

import java.io.File;
import java.util.List;
import java.util.Map;

public class TorrentFile {
    private String announce;
    private List<List<String>> announceList;
    private String comment;
    private String createdBy;
    private long creationDate;
    private String encoding;
    private Info info;

    public static class Info {
        private long pieceLength;
        private String pieces;
        private boolean isPrivate;
        private String name;
        private List<FileInfo> files;

        public static class FileInfo {
            private long length;
            private List<String> path;

            public long getLength() {
                return length;
            }

            public void setLength(long length) {
                this.length = length;
            }

            public List<String> getPath() {
                return path;
            }

            public void setPath(List<String> path) {
                this.path = path;
            }
        }

        public long getPieceLength() {
            return pieceLength;
        }

        public void setPieceLength(long pieceLength) {
            this.pieceLength = pieceLength;
        }

        public String getPieces() {
            return pieces;
        }

        public void setPieces(String pieces) {
            this.pieces = pieces;
        }

        public boolean isPrivate() {
            return isPrivate;
        }

        public void setPrivate(boolean aPrivate) {
            isPrivate = aPrivate;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public List<FileInfo> getFiles() {
            return files;
        }

        public void setFiles(List<FileInfo> files) {
            this.files = files;
        }
    }

    public String getAnnounce() {
        return announce;
    }

    public void setAnnounce(String announce) {
        this.announce = announce;
    }

    public List<List<String>> getAnnounceList() {
        return announceList;
    }

    public void setAnnounceList(List<List<String>> announceList) {
        this.announceList = announceList;
    }

    public String getComment() {
        return comment;
    }

    public void setComment(String comment) {
        this.comment = comment;
    }

    public String getCreatedBy() {
        return createdBy;
    }

    public void setCreatedBy(String createdBy) {
        this.createdBy = createdBy;
    }

    public long getCreationDate() {
        return creationDate;
    }

    public void setCreationDate(long creationDate) {
        this.creationDate = creationDate;
    }

    public String getEncoding() {
        return encoding;
    }

    public void setEncoding(String encoding) {
        this.encoding = encoding;
    }

    public Info getInfo() {
        return info;
    }

    public void setInfo(Info info) {
        this.info = info;
    }
} 
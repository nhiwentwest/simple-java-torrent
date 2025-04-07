package com.torrent;

import org.apache.commons.io.FileUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class TorrentFileParser {
    private static final Logger logger = LoggerFactory.getLogger(TorrentFileParser.class);

    public static TorrentFile parse(File torrentFile) throws IOException {
        byte[] data = FileUtils.readFileToByteArray(torrentFile);
        BencodeParser parser = new BencodeParser(data);
        Map<String, Object> dict = (Map<String, Object>) parser.parse();

        TorrentFile result = new TorrentFile();
        result.setAnnounce((String) dict.get("announce"));
        result.setAnnounceList(parseAnnounceList(dict.get("announce-list")));
        result.setComment((String) dict.get("comment"));
        result.setCreatedBy((String) dict.get("created by"));
        result.setCreationDate((Long) dict.get("creation date"));
        result.setEncoding((String) dict.get("encoding"));

        Map<String, Object> info = (Map<String, Object>) dict.get("info");
        TorrentFile.Info torrentInfo = new TorrentFile.Info();
        torrentInfo.setPieceLength((Long) info.get("piece length"));
        torrentInfo.setPieces((String) info.get("pieces"));
        torrentInfo.setPrivate(info.containsKey("private") && (Long) info.get("private") == 1);
        torrentInfo.setName((String) info.get("name"));

        if (info.containsKey("files")) {
            List<TorrentFile.Info.FileInfo> files = new ArrayList<>();
            List<Map<String, Object>> fileList = (List<Map<String, Object>>) info.get("files");
            for (Map<String, Object> fileInfo : fileList) {
                TorrentFile.Info.FileInfo file = new TorrentFile.Info.FileInfo();
                file.setLength((Long) fileInfo.get("length"));
                file.setPath((List<String>) fileInfo.get("path"));
                files.add(file);
            }
            torrentInfo.setFiles(files);
        }

        result.setInfo(torrentInfo);
        return result;
    }

    private static List<List<String>> parseAnnounceList(Object announceList) {
        if (announceList == null) {
            return null;
        }
        List<List<String>> result = new ArrayList<>();
        List<List<String>> list = (List<List<String>>) announceList;
        for (List<String> tier : list) {
            result.add(new ArrayList<>(tier));
        }
        return result;
    }

    public static byte[] calculateInfoHash(File torrentFile) throws IOException, NoSuchAlgorithmException {
        byte[] data = FileUtils.readFileToByteArray(torrentFile);
        BencodeParser parser = new BencodeParser(data);
        Map<String, Object> dict = (Map<String, Object>) parser.parse();
        Map<String, Object> info = (Map<String, Object>) dict.get("info");
        byte[] infoBytes = BencodeParser.encode(info);
        
        MessageDigest digest = MessageDigest.getInstance("SHA-1");
        return digest.digest(infoBytes);
    }
} 
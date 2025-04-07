# Java BitTorrent Client

A Java implementation of a BitTorrent client that supports:
- Multi-peer download
- Multi-peer upload
- Multi-file torrents
- Progress tracking and resuming
- Tracker communication



## Install dependencies:
```bash
mvn clean install
```

## Running the Application

### 1. Start Tracker Server

First, start the tracker server on one machine:

```bash
mvn exec:java -Dexec.mainClass="com.torrent.TrackerServer"
```

The tracker server will start listening on port 6969 by default.

### 2. Run Torrent Client

On another machine, run the torrent client:

```bash
mvn exec:java -Dexec.mainClass="com.torrent.TorrentClient" -Dexec.args="<torrent_file> <download_directory>"
```

Where:
- `<torrent_file>`: Path to the .torrent file you want to download
- `<download_directory>`: Directory where downloaded files will be saved

Example:
```bash
mvn exec:java -Dexec.mainClass="com.torrent.TorrentClient" -Dexec.args="/path/to/file.torrent /path/to/download/dir"
```

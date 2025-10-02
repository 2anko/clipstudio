package videocutter.model;

import videocutter.service.FfmpegService;
import videocutter.service.TempFiles;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

public class VideoRepository {
    private final String url;
    private final FfmpegService ff = new FfmpegService();

    public VideoRepository(String dbFile) {
        this.url = "jdbc:sqlite:" + dbFile;
        init();
    }

    private void init() {
        try (Connection c = DriverManager.getConnection(url); Statement s = c.createStatement()) {
            s.executeUpdate("CREATE TABLE IF NOT EXISTS videos (" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                    "title TEXT, duration_ms INTEGER, width INTEGER, height INTEGER, created_at TEXT, data BLOB)");
        } catch (SQLException e) { throw new RuntimeException(e); }
    }

    public void importMp4(Path file) {
        try {
            FfmpegService.Meta meta = ff.probe(file);
            try (Connection c = DriverManager.getConnection(url);
                 PreparedStatement ps = c.prepareStatement("INSERT INTO videos(title,duration_ms,width,height,created_at,data) VALUES(?,?,?,?,?,?)")) {
                ps.setString(1, file.getFileName().toString());
                ps.setLong(2, meta.durationMs);
                ps.setInt(3, meta.width);
                ps.setInt(4, meta.height);
                ps.setString(5, Instant.now().toString());
                ps.setBytes(6, Files.readAllBytes(file));
                ps.executeUpdate();
            }
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    public List<VideoAsset> listAll() {
        List<VideoAsset> out = new ArrayList<>();
        try (Connection c = DriverManager.getConnection(url);
             PreparedStatement ps = c.prepareStatement("SELECT id,title,duration_ms,width,height,created_at FROM videos ORDER BY id DESC");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                out.add(new VideoAsset(
                        rs.getLong(1), rs.getString(2), rs.getLong(3), rs.getInt(4), rs.getInt(5), Instant.parse(rs.getString(6))
                ));
            }
        } catch (SQLException e) { throw new RuntimeException(e); }
        return out;
    }

    public VideoAsset findById(long id) {
        try (Connection c = DriverManager.getConnection(url);
             PreparedStatement ps = c.prepareStatement("SELECT id,title,duration_ms,width,height,created_at FROM videos WHERE id=?")) {
            ps.setLong(1, id);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) {
                return new VideoAsset(rs.getLong(1), rs.getString(2), rs.getLong(3), rs.getInt(4), rs.getInt(5), Instant.parse(rs.getString(6)));
            }
            return null;
        } catch (SQLException e) { throw new RuntimeException(e); }
    }

    // Writes the BLOB to a temp file, returns File. Caller can delete after use.
    public File materializeToTemp(long id) {
        try (Connection c = DriverManager.getConnection(url);
             PreparedStatement ps = c.prepareStatement("SELECT data,title FROM videos WHERE id=?")) {
            ps.setLong(1, id);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) {
                byte[] bytes = rs.getBytes(1);
                String title = rs.getString(2);
                File f = TempFiles.tmp("asset-" + id + "-" + title, ".mp4").toFile();
                try (FileOutputStream fos = new FileOutputStream(f)) { fos.write(bytes); }
                return f;
            }
        } catch (Exception e) { throw new RuntimeException(e); }
        throw new IllegalArgumentException("Asset not found: " + id);
    }

    public void deleteById(long id) {
        try (Connection c = DriverManager.getConnection(url);
             PreparedStatement ps = c.prepareStatement("DELETE FROM videos WHERE id=?")) {
            ps.setLong(1, id);
            ps.executeUpdate();
        } catch (SQLException e) { throw new RuntimeException(e); }
    }

    public void deleteByIds(java.util.List<Long> ids) {
        if (ids == null || ids.isEmpty()) return;
        String placeholders = ids.stream().map(x -> "?").collect(Collectors.joining(","));
        String sql = "DELETE FROM videos WHERE id IN (" + placeholders + ")";
        try (Connection c = DriverManager.getConnection(url);
             PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < ids.size(); i++) ps.setLong(i + 1, ids.get(i));
            ps.executeUpdate();
        } catch (SQLException e) { throw new RuntimeException(e); }
    }
}

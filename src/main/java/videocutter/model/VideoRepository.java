package videocutter.model;

import videocutter.service.FfmpegService;
import videocutter.service.TempFiles;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.sql.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

public class VideoRepository {
    private final String url;
    private final FfmpegService ff = new FfmpegService();

    // NEW: local “media vault” for imported files
    private final Path vaultDir;

    public VideoRepository(String dbFile) {
        this.url = "jdbc:sqlite:" + dbFile;
        this.vaultDir = Paths.get("media");
        try { Files.createDirectories(vaultDir); } catch (IOException e) { throw new RuntimeException(e); }
        init();
    }

    private void init() {
        try (Connection c = DriverManager.getConnection(url); Statement s = c.createStatement()) {
            // add both columns so we’re compatible with old and new schemas
            s.executeUpdate("CREATE TABLE IF NOT EXISTS videos (" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                    "title TEXT, duration_ms INTEGER, width INTEGER, height INTEGER, " +
                    "created_at TEXT, path TEXT, data BLOB)");
            // If upgrading from an older schema missing `path`, it will throw & be ignored
            try { s.executeUpdate("ALTER TABLE videos ADD COLUMN path TEXT"); } catch (SQLException ignore) {}
        } catch (SQLException e) { throw new RuntimeException(e); }

        // one-time migration: move any BLOB rows to files and null the BLOB
        migrateBlobsToVault();
    }

    public void importMp4(Path file) {
        try {
            FfmpegService.Meta meta = ff.probe(file);

            // safe filename in vault
            String safeName = file.getFileName().toString().replaceAll("[\\\\/:*?\"<>|]", "_");
            Path dest = vaultDir.resolve(System.currentTimeMillis() + "-" + safeName);
            Files.copy(file, dest, StandardCopyOption.REPLACE_EXISTING);

            try (Connection c = DriverManager.getConnection(url);
                 PreparedStatement ps = c.prepareStatement(
                         "INSERT INTO videos(title,duration_ms,width,height,created_at,path,data) VALUES(?,?,?,?,?,?,NULL)")) {
                ps.setString(1, file.getFileName().toString());
                ps.setLong(2, meta.durationMs);
                ps.setInt(3, meta.width);
                ps.setInt(4, meta.height);
                ps.setString(5, Instant.now().toString());
                ps.setString(6, dest.toAbsolutePath().toString());
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
                        rs.getLong(1), rs.getString(2), rs.getLong(3),
                        rs.getInt(4), rs.getInt(5), Instant.parse(rs.getString(6))
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
             PreparedStatement ps = c.prepareStatement("SELECT path,data,title FROM videos WHERE id=?")) {
            ps.setLong(1, id);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) {
                String path = rs.getString(1);
                if (path != null && !path.isBlank() && Files.exists(Path.of(path))) {
                    return new File(path); // use the real on-disk file
                }
                // LEGACY fallback: if we still have BLOB data for some reason
                byte[] bytes = rs.getBytes(2);
                String title = rs.getString(3);
                File f = TempFiles.tmp("asset-" + id + "-" + (title == null ? "clip" : title), ".mp4").toFile();
                try (FileOutputStream fos = new FileOutputStream(f)) { fos.write(bytes); }
                return f;
            }
        } catch (Exception e) { throw new RuntimeException(e); }
        throw new IllegalArgumentException("Asset not found: " + id);
    }

    public void deleteById(long id) { deleteByIds(List.of(id)); }

    public void deleteByIds(List<Long> ids) {
        if (ids == null || ids.isEmpty()) return;
        String placeholders = ids.stream().map(x -> "?").collect(Collectors.joining(","));
        try (Connection c = DriverManager.getConnection(url)) {
            // collect paths
            List<Path> toDelete = new ArrayList<>();
            try (PreparedStatement sel = c.prepareStatement("SELECT path FROM videos WHERE id IN (" + placeholders + ")")) {
                for (int i = 0; i < ids.size(); i++) sel.setLong(i + 1, ids.get(i));
                ResultSet rs = sel.executeQuery();
                while (rs.next()) {
                    String p = rs.getString(1);
                    if (p != null && !p.isBlank()) toDelete.add(Path.of(p));
                }
            }
            // delete rows
            try (PreparedStatement del = c.prepareStatement("DELETE FROM videos WHERE id IN (" + placeholders + ")")) {
                for (int i = 0; i < ids.size(); i++) del.setLong(i + 1, ids.get(i));
                del.executeUpdate();
            }
            // delete files only if inside the vault (safety)
            for (Path p : toDelete) {
                try {
                    if (p.normalize().startsWith(vaultDir.normalize())) {
                        Files.deleteIfExists(p);
                    }
                } catch (IOException ignore) {}
            }
        } catch (SQLException e) { throw new RuntimeException(e); }
    }

    /** One-time migration: move legacy BLOBs into files and set their path. */
    private void migrateBlobsToVault() {
        try (Connection c = DriverManager.getConnection(url);
             PreparedStatement ps = c.prepareStatement("SELECT id,title,data FROM videos WHERE path IS NULL AND data IS NOT NULL");
             ResultSet rs = ps.executeQuery()) {

            while (rs.next()) {
                long id = rs.getLong(1);
                String title = rs.getString(2);
                byte[] bytes = rs.getBytes(3);
                if (bytes == null || bytes.length == 0) continue;

                String safe = (title == null ? "untitled" : title).replaceAll("[\\\\/:*?\"<>|]", "_");
                if (!safe.toLowerCase().endsWith(".mp4")) safe += ".mp4";
                Path dest = vaultDir.resolve(id + "-" + safe);

                Files.write(dest, bytes);

                try (PreparedStatement upd = c.prepareStatement("UPDATE videos SET path=?, data=NULL WHERE id=?")) {
                    upd.setString(1, dest.toAbsolutePath().toString());
                    upd.setLong(2, id);
                    upd.executeUpdate();
                }
            }
        } catch (Exception e) {
            System.err.println("Migration warning: " + e.getMessage());
        }
    }
}

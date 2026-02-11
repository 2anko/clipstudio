package videocutter.model;

import videocutter.service.FfmpegService;
import videocutter.service.TempFiles;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
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

    // local “media vault” for imported/derived files
    private final Path vaultDir;

    /** Create a unique, safe MP4 path inside the vault. Does not create the file. */
    public Path allocateVaultMp4(String suggestedName) {
        String safe = (suggestedName == null ? "clip.mp4" : suggestedName)
                .replaceAll("[\\\\/:*?\"<>|]", "_");
        if (!safe.toLowerCase().endsWith(".mp4")) safe += ".mp4";
        return vaultDir.resolve(System.currentTimeMillis() + "-" + safe)
                .toAbsolutePath().normalize();
    }

    public VideoRepository(String dbFile) throws SQLException {
        this.url = "jdbc:sqlite:" + dbFile;
        this.vaultDir = Paths.get("media").toAbsolutePath().normalize();
        try { Files.createDirectories(vaultDir); } catch (IOException e) { throw new RuntimeException(e); }
        init();
    }

    private void init() throws SQLException {
        try (Connection c = DriverManager.getConnection(url);
             Statement s = c.createStatement()) {

            s.executeUpdate("""
            CREATE TABLE IF NOT EXISTS videos (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                title TEXT,
                duration_ms INTEGER,
                width INTEGER,
                height INTEGER,
                created_at TEXT,
                path TEXT,
                edit_path TEXT,
                data BLOB,
                is_temp INTEGER DEFAULT 0,
                is_hidden INTEGER DEFAULT 0,
                parent_id INTEGER
            )
            """);
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }

        ensureColumns();
        migrateBlobsToVault();
    }

    private void ensureColumns() throws SQLException {
        try (Connection c = DriverManager.getConnection(url);
             Statement st = c.createStatement()) {
            try { st.executeUpdate("ALTER TABLE videos ADD COLUMN is_temp INTEGER DEFAULT 0"); } catch (SQLException ignored) {}
            try { st.executeUpdate("ALTER TABLE videos ADD COLUMN parent_id INTEGER"); } catch (SQLException ignored) {}
            try { st.executeUpdate("ALTER TABLE videos ADD COLUMN path TEXT"); } catch (SQLException ignored) {}
            try { st.executeUpdate("ALTER TABLE videos ADD COLUMN edit_path TEXT"); } catch (SQLException ignored) {}
            try { st.executeUpdate("ALTER TABLE videos ADD COLUMN is_hidden INTEGER DEFAULT 0"); } catch (SQLException ignored) {}
        }
    }

    public Path ensureEditCopy(long id) {
        // Option A: no edit copies. Return the original path.
        return materializeToTemp(id).toPath();
    }

    /** Hide an asset from the library list without deleting it (not used by default). */
    public void hideAsset(long id) {
        try (Connection c = DriverManager.getConnection(url);
             PreparedStatement ps = c.prepareStatement("UPDATE videos SET is_hidden=1 WHERE id=?")) {
            ps.setLong(1, id);
            ps.executeUpdate();
        } catch (SQLException e) { throw new RuntimeException(e); }
    }

    public long insertDerivedVideo(String title, Path filePath, long parentId) throws SQLException {
        FfmpegService.Meta meta = ff.probe(filePath);

        String sql = """
        INSERT INTO videos(title,duration_ms,width,height,created_at,path,edit_path,data,is_temp,is_hidden,parent_id)
        VALUES(?,?,?,?,?,?,NULL,NULL,0,0,?)
    """;

        try (Connection c = DriverManager.getConnection(url);
             PreparedStatement ps = c.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {

            ps.setString(1, title);
            ps.setLong(2, meta.durationMs);
            ps.setInt(3, meta.width);
            ps.setInt(4, meta.height);
            ps.setString(5, Instant.now().toString());
            ps.setString(6, filePath.toAbsolutePath().normalize().toString());
            ps.setLong(7, parentId);

            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }


    public long insertTempVideo(String title, Path filePath, long durationMs, long parentId) throws SQLException {
        String sql = "INSERT INTO videos(title,duration_ms,width,height,created_at,path,data,is_temp,parent_id) " +
                "VALUES(?,?,?,?,?,?,NULL,?,?)";

        try (Connection c = DriverManager.getConnection(url);
             PreparedStatement ps = c.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {

            ps.setString(1, title);
            ps.setLong(2, durationMs);
            ps.setInt(3, 0);
            ps.setInt(4, 0);
            ps.setString(5, Instant.now().toString());
            ps.setString(6, filePath.toAbsolutePath().toString());
            ps.setInt(7, 1);
            ps.setLong(8, parentId);

            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    public void deleteAllTempVideos() throws SQLException {
        List<Path> paths = new ArrayList<>();

        try (Connection c = DriverManager.getConnection(url)) {
            try (PreparedStatement ps = c.prepareStatement("SELECT path FROM videos WHERE is_temp = 1");
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String p = rs.getString(1);
                    if (p != null && !p.isBlank()) paths.add(Paths.get(p));
                }
            }

            try (PreparedStatement ps = c.prepareStatement("DELETE FROM videos WHERE is_temp = 1")) {
                ps.executeUpdate();
            }
        }

        for (Path p : paths) {
            try { Files.deleteIfExists(p); } catch (IOException ignored) {}
        }
    }

    public long importMp4(Path file) {
        try {
            FfmpegService.Meta meta = ff.probe(file);

            String safeName = file.getFileName().toString().replaceAll("[\\\\/:*?\"<>|]", "_");
            Path dest = vaultDir.resolve(System.currentTimeMillis() + "-" + safeName);
            Files.copy(file, dest, StandardCopyOption.REPLACE_EXISTING);

            try (Connection c = DriverManager.getConnection(url);
                 PreparedStatement ps = c.prepareStatement(
                         "INSERT INTO videos(title,duration_ms,width,height,created_at,path,edit_path,data,is_temp,is_hidden,parent_id) " +
                                 "VALUES(?,?,?,?,?,?,NULL,NULL,0,0,NULL)",
                         Statement.RETURN_GENERATED_KEYS)) {

                ps.setString(1, file.getFileName().toString());
                ps.setLong(2, meta.durationMs);
                ps.setInt(3, meta.width);
                ps.setInt(4, meta.height);
                ps.setString(5, Instant.now().toString());
                ps.setString(6, dest.toAbsolutePath().toString());
                ps.executeUpdate();

                try (ResultSet rs = ps.getGeneratedKeys()) {
                    rs.next();
                    return rs.getLong(1);
                }
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public List<VideoAsset> listAll() {
        List<VideoAsset> out = new ArrayList<>();
        try (Connection c = DriverManager.getConnection(url);
             PreparedStatement ps = c.prepareStatement(
                     "SELECT id,title,duration_ms,width,height,created_at FROM videos WHERE is_temp = 0 AND is_hidden = 0 ORDER BY id DESC"
             );
             ResultSet rs = ps.executeQuery()) {

            while (rs.next()) {
                out.add(new VideoAsset(
                        rs.getLong(1), rs.getString(2), rs.getLong(3),
                        rs.getInt(4), rs.getInt(5), Instant.parse(rs.getString(6))
                ));
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
        return out;
    }

    public VideoAsset findById(long id) {
        try (Connection c = DriverManager.getConnection(url);
             PreparedStatement ps = c.prepareStatement("SELECT id,title,duration_ms,width,height,created_at FROM videos WHERE id=?")) {
            ps.setLong(1, id);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) {
                return new VideoAsset(
                        rs.getLong(1),
                        rs.getString(2),
                        rs.getLong(3),
                        rs.getInt(4),
                        rs.getInt(5),
                        Instant.parse(rs.getString(6))
                );
            }
            return null;
        } catch (SQLException e) { throw new RuntimeException(e); }
    }

    // Prefer ORIGINAL path (Option A). Only use edit_path as a fallback if it actually exists.
    // Also: if edit_path is set but missing, clear it so you don't keep hitting the same failure.
    public File materializeToTemp(long id) {
        try (Connection c = DriverManager.getConnection(url);
             PreparedStatement ps = c.prepareStatement("SELECT path, edit_path, data, title FROM videos WHERE id=?")) {
            ps.setLong(1, id);
            ResultSet rs = ps.executeQuery();
            if (!rs.next()) throw new IllegalArgumentException("Asset not found: " + id);

            String path = rs.getString(1);
            String editPath = rs.getString(2);

            if (path != null && !path.isBlank()) {
                Path p = Path.of(path);
                if (Files.exists(p)) return p.toFile();
            }

            if (editPath != null && !editPath.isBlank()) {
                Path ep = Path.of(editPath);
                if (Files.exists(ep)) return ep.toFile();

                // stale edit_path -> clear it
                try (PreparedStatement upd = c.prepareStatement("UPDATE videos SET edit_path=NULL WHERE id=?")) {
                    upd.setLong(1, id);
                    upd.executeUpdate();
                }
            }

            byte[] bytes = rs.getBytes(3);
            String title = rs.getString(4);
            File f = TempFiles.tmp("asset-" + id + "-" + (title == null ? "clip" : title), ".mp4").toFile();
            try (FileOutputStream fos = new FileOutputStream(f)) { fos.write(bytes); }
            return f;

        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }


    public void deleteById(long id) { deleteByIds(List.of(id)); }

    public void deleteByIds(List<Long> ids) {
        if (ids == null || ids.isEmpty()) return;

        String placeholders = ids.stream().map(x -> "?").collect(Collectors.joining(","));
        try (Connection c = DriverManager.getConnection(url)) {
            List<Path> toDelete = new ArrayList<>();

            try (PreparedStatement sel = c.prepareStatement("SELECT path, edit_path FROM videos WHERE id IN (" + placeholders + ")")) {
                for (int i = 0; i < ids.size(); i++) sel.setLong(i + 1, ids.get(i));
                ResultSet rs = sel.executeQuery();
                while (rs.next()) {
                    String p1 = rs.getString(1);
                    String p2 = rs.getString(2);
                    if (p1 != null && !p1.isBlank()) toDelete.add(Path.of(p1));
                    if (p2 != null && !p2.isBlank()) toDelete.add(Path.of(p2));
                }
            }

            try (PreparedStatement del = c.prepareStatement("DELETE FROM videos WHERE id IN (" + placeholders + ")")) {
                for (int i = 0; i < ids.size(); i++) del.setLong(i + 1, ids.get(i));
                del.executeUpdate();
            }

            for (Path p : toDelete) {
                try {
                    Path abs = p.toAbsolutePath().normalize();
                    if (abs.startsWith(vaultDir)) Files.deleteIfExists(abs);
                    else System.err.println("Skip delete (outside vault): " + abs);
                } catch (IOException ex) {
                    System.err.println("Failed to delete file: " + p + " | " + ex.getMessage());
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    /** One-time migration: move legacy BLOBs into files and set their path. */
    private void migrateBlobsToVault() {
        try (Connection c = DriverManager.getConnection(url);
             PreparedStatement ps = c.prepareStatement(
                     "SELECT id,title,data FROM videos WHERE is_temp = 0 AND path IS NULL AND data IS NOT NULL"
             );
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

package videocutter.service;


import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;


public class TempFiles {
    public static Path tmp(String prefix, String ext) {
        try { return Files.createTempFile(prefix, ext); }
        catch (IOException e) { throw new RuntimeException(e); }
    }
}
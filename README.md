# ClipStudio — JavaFX Video Cutter (MVP)

A lightweight, Clipchamp-style MVP built with **Java 17 + JavaFX**, using **FFmpeg** for cutting/merging and **SQLite** for metadata.  
Videos are stored on disk in a local `media/` vault; the DB stores **paths + metadata** (duration, width/height, createdAt).

https://github.com/2anko/clipstudio

---

## Features (current)

- 🎞️ **Library**: import MP4 files (drag & drop or button). Stored in `media/`, path + metadata saved in SQLite.
- ➕ **Add to timeline**: double-click, **Enter**, or **Add → Timeline** (multi-select supported).
- ✂️ **Trim**: two-thumb range bar with smooth dragging.
- ▶️ **Preview**: plays only within the trimmed range (stops at end handle).
- 🔗 **Merge**: export concatenates trimmed clips using FFmpeg concat demuxer (stream copy by default).
- 🗑️ **Delete from DB**: removes selected assets from DB and deletes files in `media/`; timeline clips referencing them are cleared.

> Roadmap ideas: reordering clips, thumbnails/waveforms, zoomable ruler, frame-accurate cuts, progress dialog, project save/load.

---

## Screenshots

![img.png](img.png)
![img_1.png](img_1.png)


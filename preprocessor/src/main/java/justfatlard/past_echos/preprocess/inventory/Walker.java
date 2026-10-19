package justfatlard.past_echos.preprocess.inventory;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Enumeration;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;

/**
 * Walks directories and the archives inside them, nested ones included, and hands every file to a
 * sink. Nothing is extracted to disk. Paths inside archives read {@code outer.zip!/inner/file}.
 */
public final class Walker {
	private static final int MAX_ARCHIVE_DEPTH = 6;
	// Pre-UTF-8 zip tools wrote names in the DOS code page.
	private static final Charset ZIP_LEGACY_NAMES = Charset.forName("IBM437");

	private final EntrySink sink;
	private final Consumer<String> progress;

	public Walker(EntrySink sink, Consumer<String> progress) {
		this.sink = sink;
		this.progress = progress;
	}

	public void walk(Path start) throws IOException {
		Files.walkFileTree(start, new SimpleFileVisitor<>() {
			@Override
			public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
				if (attributes.isRegularFile()) {
					onDiskFile(file, attributes);
				}
				return FileVisitResult.CONTINUE;
			}

			@Override
			public FileVisitResult visitFileFailed(Path file, IOException e) {
				sink.skipped(file.toString(), "unreadable: " + e.getMessage());
				return FileVisitResult.CONTINUE;
			}
		});
	}

	private void onDiskFile(Path file, BasicFileAttributes attributes) throws IOException {
		String path = file.toString();
		ArchiveKind kind = ArchiveKind.of(path);
		if (kind == ArchiveKind.ZIP) {
			progress.accept("opening " + path);
			ZipFile zip;
			try {
				zip = openZip(file);
			} catch (IOException e) {
				sink.skipped(path, "unreadable zip: " + e.getMessage());
				return;
			}
			try (zip) {
				Enumeration<? extends ZipEntry> entries = zip.entries();
				while (entries.hasMoreElements()) {
					ZipEntry e = entries.nextElement();
					if (!e.isDirectory()) {
						entry(path + "!/" + e.getName(), e.getSize(), e.getTime(), () -> zip.getInputStream(e), 1);
					}
				}
			}
			return;
		}
		long size = attributes.size();
		long mtime = attributes.lastModifiedTime().toMillis();
		entry(path, size, mtime, () -> Files.newInputStream(file), 0);
	}

	/** Names are validated when the central directory loads, so a wrong charset fails here, before any entry is read. */
	private static ZipFile openZip(Path file) throws IOException {
		try {
			return new ZipFile(file.toFile(), StandardCharsets.UTF_8);
		} catch (ZipException | IllegalArgumentException badName) {
			return new ZipFile(file.toFile(), ZIP_LEGACY_NAMES);
		}
	}

	private void entry(String path, long size, long mtime, EntrySink.Opener content, int depth) throws IOException {
		ArchiveKind kind = ArchiveKind.of(path);
		if (kind == ArchiveKind.NONE) {
			sink.file(path, size, mtime, content);
			return;
		}
		if (kind == ArchiveKind.UNSUPPORTED) {
			sink.skipped(path, "archive format not readable yet; unpack it and scan the folder");
			return;
		}
		if (depth >= MAX_ARCHIVE_DEPTH) {
			sink.skipped(path, "archive nested " + depth + " deep");
			return;
		}
		if (depth > 0) {
			progress.accept("opening " + path);
		}
		try (InputStream raw = new BufferedInputStream(content.open(), 1 << 16)) {
			switch (kind) {
				case ZIP -> streamZip(path, raw, depth);
				case TAR -> streamTar(path, raw, depth);
				case TAR_GZ -> streamTar(path, new GZIPInputStream(raw, 1 << 16), depth);
				default -> throw new IllegalStateException(kind.name());
			}
		} catch (IOException | IllegalArgumentException e) {
			// One damaged archive shouldn't end a scan of a whole backup drive.
			sink.skipped(path, "unreadable " + kind.name().toLowerCase(Locale.ROOT) + ": " + e.getMessage());
		}
	}

	private void streamZip(String path, InputStream raw, int depth) throws IOException {
		ZipInputStream zip = new ZipInputStream(raw, StandardCharsets.UTF_8);
		for (ZipEntry e; (e = zip.getNextEntry()) != null; ) {
			if (!e.isDirectory()) {
				InputStream current = Bytes.unclosable(zip);
				entry(path + "!/" + e.getName(), e.getSize(), e.getTime(), () -> current, depth + 1);
			}
		}
	}

	private void streamTar(String path, InputStream raw, int depth) throws IOException {
		Tar.read(raw, (name, size, mtime, content) -> entry(path + "!/" + name, size, mtime, () -> content, depth + 1));
	}

	enum ArchiveKind {
		NONE, ZIP, TAR, TAR_GZ, UNSUPPORTED;

		static ArchiveKind of(String path) {
			String name = path.substring(path.lastIndexOf('/') + 1).toLowerCase(Locale.ROOT);
			if (name.endsWith(".zip") || name.endsWith(".mcworld")) {
				return ZIP;
			}
			if (name.endsWith(".tar")) {
				return TAR;
			}
			if (name.endsWith(".tar.gz") || name.endsWith(".tgz")) {
				return TAR_GZ;
			}
			for (String ext : new String[]{".7z", ".rar", ".tar.bz2", ".tbz2", ".tar.xz", ".txz", ".tar.zst", ".tzst"}) {
				if (name.endsWith(ext)) {
					return UNSUPPORTED;
				}
			}
			return NONE;
		}
	}
}

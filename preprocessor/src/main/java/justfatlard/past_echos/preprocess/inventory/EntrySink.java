package justfatlard.past_echos.preprocess.inventory;

import java.io.IOException;
import java.io.InputStream;

/**
 * Receives every file the walker finds, whether it lies on disk or inside an archive.
 *
 * <p>The content opener is valid only during the call: entries inside a streamed archive can be
 * read once, in order, and are gone when the call returns.
 */
public interface EntrySink {
	void file(String path, long size, long mtime, Opener content) throws IOException;

	void skipped(String path, String reason);

	@FunctionalInterface
	interface Opener {
		InputStream open() throws IOException;
	}
}

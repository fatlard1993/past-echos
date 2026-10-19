package justfatlard.past_echos.preprocess.regen;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

/**
 * An official server jar running headless in a scratch folder, driven through its console.
 * It listens on loopback only with a whitelist, so nothing can join it while it generates.
 */
final class Server implements AutoCloseable {
	private static final Duration STARTUP = Duration.ofMinutes(10);
	private static final Duration SHUTDOWN = Duration.ofMinutes(5);
	private static final int TAIL = 40;
	private static final String END = "\u0000end";

	private final Process process;
	private final Writer console;
	private final BlockingQueue<String> lines = new LinkedBlockingQueue<>();
	private final Deque<String> tail = new ArrayDeque<>();

	private Server(Process process) {
		this.process = process;
		this.console = process.outputWriter(StandardCharsets.UTF_8);
	}

	/**
	 * Starts the jar in dir on the world in dir/world and returns once it reports ready. Writes
	 * eula=true, accepting the Minecraft EULA (https://aka.ms/MinecraftEULA) for this server.
	 */
	static Server start(Path jar, Path dir, String maxHeap) throws IOException {
		Files.writeString(dir.resolve("eula.txt"), "eula=true\n");
		Files.writeString(dir.resolve("server.properties"), String.join("\n",
				"level-name=world",
				"server-ip=127.0.0.1",
				"server-port=0",
				"online-mode=false",
				"white-list=true",
				"enforce-whitelist=true",
				"spawn-protection=0",
				"max-tick-time=-1",
				"enable-query=false",
				"enable-rcon=false",
				""));
		Path java = Path.of(System.getProperty("java.home"), "bin", "java");
		Process process = new ProcessBuilder(java.toString(), "-Xmx" + maxHeap, "-Dlog4j2.formatMsgNoLookups=true", "-jar", jar.toString(), "nogui")
				.directory(dir.toFile())
				.redirectErrorStream(true)
				.start();
		Server server = new Server(process);
		Path log = dir.resolve("server.log");
		Thread reader = new Thread(() -> server.pump(log), "server output");
		reader.setDaemon(true);
		reader.start();
		try {
			server.await(line -> line.contains("Done ("), STARTUP);
		} catch (IOException e) {
			server.process.destroyForcibly();
			throw e;
		}
		return server;
	}

	private void pump(Path log) {
		try (BufferedReader in = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
				Writer out = Files.newBufferedWriter(log, StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
			String line;
			while ((line = in.readLine()) != null) {
				out.write(line);
				out.write('\n');
				synchronized (tail) {
					tail.addLast(line);
					if (tail.size() > TAIL) {
						tail.removeFirst();
					}
				}
				lines.add(line);
			}
		} catch (IOException e) {
			// The process is gone; await reports that from its exit.
		} finally {
			lines.add(END);
		}
	}

	void send(String command) throws IOException {
		console.write(command + "\n");
		console.flush();
	}

	/** Waits for an output line matching, failing if the server exits or time runs out first. */
	String await(Predicate<String> match, Duration timeout) throws IOException {
		long deadline = System.nanoTime() + timeout.toNanos();
		try {
			while (true) {
				long left = deadline - System.nanoTime();
				String line = left > 0 ? lines.poll(left, TimeUnit.NANOSECONDS) : null;
				if (line == null) {
					throw new IOException("server did not answer within " + timeout + ":\n" + tail());
				}
				if (line.equals(END)) {
					lines.add(END);
					throw new IOException("server exited:\n" + tail());
				}
				if (match.test(line)) {
					return line;
				}
			}
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IOException("interrupted waiting for the server", e);
		}
	}

	/** Saves and stops, waiting for the process to exit. */
	void stop() throws IOException {
		send("stop");
		try {
			if (!process.waitFor(SHUTDOWN.toSeconds(), TimeUnit.SECONDS)) {
				process.destroyForcibly();
				throw new IOException("server did not stop within " + SHUTDOWN + ":\n" + tail());
			}
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			process.destroyForcibly();
			throw new IOException("interrupted stopping the server", e);
		}
	}

	private String tail() {
		synchronized (tail) {
			return String.join("\n", tail);
		}
	}

	@Override
	public void close() {
		if (process.isAlive()) {
			process.destroyForcibly();
		}
	}
}

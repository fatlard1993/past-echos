package justfatlard.past_echos.preprocess.regen;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/**
 * Mojang's official server jars, downloaded once and checked against the SHA-1 in the version
 * manifest. Each jar keeps its expected hash beside it, so later runs need no network.
 */
public final class ServerJars {
	private static final URI MANIFEST = URI.create("https://piston-meta.mojang.com/mc/game/version_manifest_v2.json");

	private final Path cache;
	private final HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build();

	public ServerJars(Path cache) {
		this.cache = cache;
	}

	/** $PAST_ECHOS_CACHE, else $XDG_CACHE_HOME/past-echos, else ~/.cache/past-echos; jars go in server-jars/. */
	public static Path defaultCache() {
		String explicit = System.getenv("PAST_ECHOS_CACHE");
		if (explicit != null && !explicit.isBlank()) {
			return Path.of(explicit, "server-jars");
		}
		String xdg = System.getenv("XDG_CACHE_HOME");
		Path base = xdg != null && !xdg.isBlank() ? Path.of(xdg) : Path.of(System.getProperty("user.home"), ".cache");
		return base.resolve("past-echos/server-jars");
	}

	public Path jar(String version) throws IOException {
		Path jar = cache.resolve("server-" + version + ".jar");
		Path expected = cache.resolve("server-" + version + ".jar.sha1");
		if (Files.exists(jar) && Files.exists(expected) && sha1(Files.readAllBytes(jar)).equals(Files.readString(expected).strip())) {
			return jar;
		}
		Map<String, Object> server = serverDownload(version);
		String sha1 = (String) server.get("sha1");
		byte[] bytes = get(URI.create((String) server.get("url")));
		if (!sha1(bytes).equals(sha1)) {
			throw new IOException("server jar for " + version + " does not match the manifest's SHA-1");
		}
		Files.createDirectories(cache);
		Path partial = Files.createTempFile(cache, "server-" + version, ".part");
		Files.write(partial, bytes);
		Files.move(partial, jar, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		Files.writeString(expected, sha1 + "\n");
		return jar;
	}

	@SuppressWarnings("unchecked")
	private Map<String, Object> serverDownload(String version) throws IOException {
		Object manifest = Json.parse(new String(get(MANIFEST)));
		for (Object v : (List<Object>) ((Map<String, Object>) manifest).get("versions")) {
			Map<String, Object> entry = (Map<String, Object>) v;
			if (version.equals(entry.get("id"))) {
				Object detail = Json.parse(new String(get(URI.create((String) entry.get("url")))));
				Object server = Json.object(detail, "downloads").get("server");
				if (server == null) {
					throw new IOException("Mojang publishes no server jar for " + version + " (the first is 1.2.5)");
				}
				return (Map<String, Object>) server;
			}
		}
		throw new IOException(version + " is not in Mojang's version manifest");
	}

	private byte[] get(URI uri) throws IOException {
		try {
			HttpResponse<byte[]> response = http.send(HttpRequest.newBuilder(uri).build(), HttpResponse.BodyHandlers.ofByteArray());
			if (response.statusCode() != 200) {
				throw new IOException(uri + ": HTTP " + response.statusCode());
			}
			return response.body();
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IOException("interrupted fetching " + uri, e);
		}
	}

	private static String sha1(byte[] bytes) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(bytes));
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
	}
}

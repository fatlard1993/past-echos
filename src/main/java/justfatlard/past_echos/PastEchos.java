package justfatlard.past_echos;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import justfatlard.past_echos.echo.Library;
import justfatlard.past_echos.worldgen.EchoStructures;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class PastEchos implements ModInitializer {
	public static final String MOD_ID = "past-echos-justfatlard";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	@Override
	public void onInitialize() {
		EchoStructures.register();
		// Before any level loads, so the first chunks generated can already hold a ruin.
		ServerLifecycleEvents.SERVER_STARTING.register(server -> loadLibrary());
	}

	private static void loadLibrary() {
		Path folder = FabricLoader.getInstance().getGameDir().resolve("past-echos");
		try {
			Files.createDirectories(folder);
			Library library = Library.load(folder);
			Library.use(library);
			LOGGER.info("{} ruins from {}", library.ruins().size(), folder);
		} catch (IOException e) {
			LOGGER.error("Could not read echoes from {}", folder, e);
		}
	}
}

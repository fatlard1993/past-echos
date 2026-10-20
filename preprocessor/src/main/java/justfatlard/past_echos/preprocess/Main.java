package justfatlard.past_echos.preprocess;

import java.util.Arrays;
import justfatlard.past_echos.preprocess.diff.Diff;
import justfatlard.past_echos.preprocess.extract.Extract;
import justfatlard.past_echos.preprocess.inventory.Inventory;

/** The preprocessor's command line: past-echos inventory|diff|extract ... */
public final class Main {
	private Main() {
	}

	public static void main(String[] args) throws Exception {
		String[] rest = args.length == 0 ? args : Arrays.copyOfRange(args, 1, args.length);
		switch (args.length == 0 ? "" : args[0]) {
			case "inventory" -> Inventory.main(rest);
			case "diff" -> Diff.main(rest);
			case "extract" -> Extract.main(rest);
			default -> {
				System.err.println("usage: past-echos inventory|diff|extract ...");
				System.err.println();
				System.err.println("  inventory  list the worlds under folders and archives, and how each can be regenerated");
				System.err.println("  diff       regenerate a world's untouched terrain and record where the saved world differs");
				System.err.println("  extract    sort a diff into player work and noise, and cut the work out as echoes");
				System.exit(2);
			}
		}
	}
}

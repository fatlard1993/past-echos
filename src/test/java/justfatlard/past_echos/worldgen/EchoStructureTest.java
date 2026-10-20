package justfatlard.past_echos.worldgen;

import static org.junit.jupiter.api.Assertions.assertEquals;

import justfatlard.past_echos.preprocess.extract.Extract;
import org.junit.jupiter.api.Test;

class EchoStructureTest {
	@Test
	void extractCutsNothingWiderThanCanBePlaced() {
		assertEquals(EchoStructure.MAX_SPAN, Extract.MAX_SPAN);
	}
}

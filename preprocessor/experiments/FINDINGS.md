# Regeneration noise (determinism.py, seed 8675309, 2026-10-19)

The same seed generated three times per version: the usual first start, the same again
(control), and the same chunks reached from a neighbouring area first (moved). Compared over
chunks decorated in all three, their neighbours too.

| version | control: chunks differing / blocks | moved: chunks differing / blocks | per chunk (moved, median / max) |
|---------|------------------------------------|----------------------------------|---------------------------------|
| 1.2.5   | 0/256 / 0                          | 126/256 / 109,656                | 0 / 2,326                       |
| 1.6.4   | 0/256 / 0                          | 142/256 / 133,975                | 176 / 3,068                     |
| 1.8.9   | 0/256 / 0                          | 8/256 / 753                      | 0 / 249                         |
| 1.12.2  | 0/256 / 0                          | 17/256 / 1,759                   | 0 / 249                         |
| 1.16.5  | 266/694 / 16,571                   | 315/694 / 14,935                 | 0 / 1,069                       |
| 1.18.2  | 387/492 / 34,381                   | 406/492 / 39,379                 | 16 / 891                        |
| 1.20.4  | 269/492 / 24,022                   | 340/492 / 37,993                 | 16 / 938                        |
| 26.3    | 191/289 / 45,677                   | 269/289 / 75,768                 | 146 / 1,251                     |

- Terrain never differs: stone against air, caves, ravines, aquifer outlines. Player digging
  and anything built in open air stand out against that cleanly.
- Up to 1.12 generation is deterministic for a given order. 1.2.5 and 1.6.4 are very sensitive
  to order: about half the chunks decorate differently. 1.8.9 and 1.12.2 hardly are.
- From 1.16 generation is not reproducible even in the same order, presumably from
  multi-threaded decoration of overlapping features. Moving the order adds little on top.
- The noise is almost entirely natural vocabulary. Trees (leaves, logs, vines) are 40-80% of
  it, then (from 1.16) ore blobs and granite/diorite/andesite swapping inside stone, then
  surface plants, sand/dirt patches, lakes and springs, snow.
- The exceptions look like player work: mineshaft rails, planks and cobwebs (1.16.5), bee
  nests, cobblestone/obsidian from lava meeting water. Structure pieces need their own handling.
- Per-chunk noise reaches 1,000-3,000 blocks, more than many small builds, so no block-count
  threshold separates the two. Separation has to come from which blocks changed and how.
- State noise: leaves' distance property changes with neighbour updates (a fifth of 26.3's
  moved diffs). persistent=true leaves (meta bit 0x4 before 1.13) are placed, so a signal.
- 26.x omits default property values (deepslate for deepslate[axis=y], water for
  water[level=0]); 26.x palettes mix strings and {id, properties}, wrapped as {"": ...}.

Implication: a (reference, saved) block-pair noise table, learned from runs like these, scores
diffs, and connected components of unlikely pairs are the substance.

# Extraction calibration (stage 2, 2026-10-19)

Clusters of evidence-carrying blocks (Evidence.java weights, within 2 blocks on every axis),
scored by summed weight. Noise: the determinism runs' A against moved, 8 versions, 570k blocks
(src/test/resources/diffs/*-noise). Builds: the test hut in each version's regenerated diff.

| | highest score | what it was |
|-|---------------|-------------|
| noise, all weights | 68.5 | a lake carved in one generation only: 274 dirt/stone -> air (1.16.5) |
| noise, earthwork left out | 4.6 | mineshaft fragments: rails gone, cobwebs and planks moved (1.16.5) |
| test hut, every version | 110.5 | 168 evidence blocks |
| test tunnel (32 dug blocks) | 0 (3-8 with earthwork) | |

- Digging can't be told from regeneration's lakes, springs and falling gravel by amount: a lake
  outscores a 16-block tunnel eightfold. Earthwork extends a cluster but never makes an echo.
- With earthwork out, this corpus tops out under half the threshold of 10.

Held out: ten fresh untouched worlds on seeds 1001, 31337 and 2024, through the real pipeline
(generate, past-echos diff, past-echos extract). Four produced "echoes" the tuning corpus never
showed, all generator work:

| world | false echoes | what it was |
|-------|--------------|-------------|
| 26.3 seed 31337 | 29, up to 765 | an ancient city: sculk spreads over its bricks differently each time |
| 1.18.2 seed 1001 | 1, 50 | a dungeon one generation placed and the other didn't |
| 1.18.2 seed 31337 | 2, 40 | cobblestone columns where lava met water |
| 1.16.5 seed 2024 | 1, 11 | the same |

- Anything touching sculk now weighs nothing, and scattered structure blocks (dungeon walls,
  mineshafts) weigh nothing when they disappear.
- Cobblestone alone is ambiguous: the generator makes it by the dozen. No noise cluster held
  more than one block of the built kind (doors, glass, crafting tables, bricks), so an echo now
  needs 3. The cost: a bare cobblestone wall or plank bridge, with nothing else, is lost.
- One seed was not enough vocabulary; these four diffs are now test fixtures. Since they shaped
  the rules, a second fresh batch checks them.

Second batch: twelve worlds, seeds 777, 90210 and 5150, every version. Eleven were clean (the
highest rejected cluster scored 68.5 with no placed blocks); two 26.3 worlds were not:

| world | false echoes | what it was |
|-------|--------------|-------------|
| 26.3 seed 777 | 13, up to 813 | sulfur caves: sulfur, cinnabar, sulfur_spike, all new to the vocabulary |
| 26.3 seed 5150 | 2, up to 434 | the same |

- Unknown blocks default to built, so each new version's natural blocks are false evidence
  until listed. A list can't keep up, but the diff knows: regeneration noise is symmetric, so a
  block common on the reference side (16 or more, and a quarter of its saved-side count) is the
  generator's. That learned sulfur and cinnabar, and on its own the ancient city's bricks.
- sulfur_spike was lopsided (7 reference, 63 saved): it grows while the world runs, like
  pointed dripstone. What's left of such a cluster is nearly one block, so an echo's commonest
  placed or structure block may be at most 80% of them. The test hut's is 40%. The cost: a
  build of one material alone, such as a glass dome, is lost.
- After both fixes all 34 noise diffs give no echoes and all 8 huts do. The sulfur worlds are
  fixtures now too, so the next fresh batch is the honest check.

Third batch: twelve worlds, seeds 4242, 8008 and 123456789, every version. Eleven clean; one
false echo, barely over the line (score 12.1, 9 placed): an ocean ruin in 1.20.4, whose bricks
crumble at random, so regeneration lost some and gained others in the same spot.

- Within one cluster, a block that is both lost and gained is a structure regenerated
  differently: a player's blocks are only gained. Such blocks still give the cluster its
  extent but count toward nothing. The ruin's diff is a fixture now.
- Totals after: 48 noise diffs (35 worlds and the determinism runs), no echoes; 8 huts, found.
- Each batch so far turned up something, smaller each time: an ancient city, then sulfur caves,
  then one ruin worth 12. Real worlds will find more; extract's echoes.json is where to look.

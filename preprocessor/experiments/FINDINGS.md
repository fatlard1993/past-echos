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

# Past Echos

A Fabric mod that brings what you built in your old worlds into a new one, as weathered ruins.

Point its preprocessor at your old saves and it finds what players built, by comparing each world against untouched terrain grown from the same seed. It cuts those builds out, and the mod scatters them through land the new world has not generated yet: your first house, a friend's tower, the farm from years ago, standing half-fallen in a field.

## In the World

- **Where**: the overworld, on dry ground that is near enough level. A long ruin may stand on more of a slope than a small one, and the ground is built up or cut back around its base to meet it.
- **How often**: one chance in every 24 by 24 chunks (about 384 blocks each way), never closer than 8 chunks to the next.
- **Only new land**: chunks generated after the echoes are installed. Ground you have already explored is left as it is.
- **The same every time**: a seed puts the same ruins in the same places for as long as the same echoes are installed.

Ruins weather as they are placed:

- stone bricks crack or grow moss, and cobblestone grows moss
- about half the glass and glass panes are gone
- most torches have burnt out

Worlds saved by any version from 1.2.5 to 26.3 can be read. A ruin's blocks and signs are brought up to date by the game's own data fixers, the same upgrade an old world gets when it is opened.

## Making Echoes

The ruins come from the `past-echos` command-line tool, which reads old worlds on your own machine. Making them takes three steps, and installing them a fourth.

**1. Find your worlds.**

```
past-echos inventory ~/old-saves backups.zip
```

Lists every world under the folders and archives you give it (zips, tars and tar.gz, archives inside archives too): what version made it, its seed, how many chunks it holds, and how its untouched terrain can be regenerated. It only reads.

**2. Diff a world against fresh terrain.**

```
past-echos diff ~/old-saves/MyWorld
```

Downloads Mojang's server for the version that made the world, runs it to regenerate the world's untouched terrain from its seed, and records every block where the saved world differs. Running Mojang's server this way accepts the [Minecraft EULA](https://aka.ms/MinecraftEULA). The result goes in `MyWorld-diff` beside the world.

- `--version 1.2.5`: worlds before 1.9 do not say which version made them, so give it.
- `--min-inhabited TICKS`: only chunks a player spent this long near, a minute by default, so land a player only passed through is skipped. Worlds before 1.6 do not record it and keep every chunk.
- `--reference OLDER`: diff against an untouched earlier copy of the same world instead of a regeneration. No regeneration noise, and it works for any version or mod, but chunks the copy lacks are skipped.
- `--saved-only`: no regeneration at all. Every visited block that is not natural counts, outside the structures the world records. Works for any version, seed or mod, but finds more that is not yours.
- `--heap 4G`: memory for the regenerating server, 3G by default.

**3. Cut out the builds.**

```
past-echos extract ~/old-saves/MyWorld
```

Sorts the diff into player work and the noise any regeneration has (trees, ore and lakes never grow back quite the same), and cuts each piece of work out of the saved world with the ground around it. It prints each echo's size and where it stood, and writes `echoes.gz` into the diff folder.

**4. Install them.** Copy the diff folder, or just its `echoes.gz`, into the `past-echos` folder beside the server's `mods` folder. The server makes that folder on its first start, and reads every `echoes.gz` under it, at any depth, each time it starts. Take a file away and ruins from it that have not been generated yet place nothing; ruins already standing stay.

## The Tool

The `past-echos` tool is in this repository's `preprocessor` folder and needs Java 25. Build it with:

```
./gradlew :preprocessor:installDist
```

and run it as `preprocessor/build/install/past-echos/bin/past-echos`.

## Server-side Only

Unlike the rest of this suite, Past Echos needs no Pandorical. The ruins are ordinary blocks, so players join with an unmodded game. Install it on the server, or in a single-player game's mods folder.

## Early Days

This is 0.1.0. Telling a build from generated terrain is the hard part, and it is still being tuned: expect the odd ruin that is a scrap of a village rather than anything you built, and the odd build that is missed. Look over what `extract` prints before installing a world's echoes.

## Development

How the extractor tells builds from noise, and the measurements behind it, are in [preprocessor/experiments/FINDINGS.md](preprocessor/experiments/FINDINGS.md).

## License

MIT, see [LICENSE](LICENSE).

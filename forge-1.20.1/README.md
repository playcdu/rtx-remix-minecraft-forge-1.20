# Forge 1.20.1 port for Society: Sunlit Valley

This standalone module builds the renderer for **Minecraft 1.20.1 on Forge
47.4**, so it can run inside the
[Society: Sunlit Valley](https://modrinth.com/modpack/society-sunlit-valley)
modpack (tuned against pack version 4.1.5B). It is a port of the Fabric 1.21.1
mod in the repository root: the same renderer, terrain pipeline, entity and
particle capture, lights, Copper Beacon, settings screens, `/rtx` commands,
camera paths and conf layers, rebuilt on Forge, official Mojang names and
Java 17. It shares the JNI facade in [`../rtxremix-java`](../rtxremix-java) and
the runtime, conf payload and textures the root build packages. The port is
experimental: it compiles and passes its JVM tests, but rendering is verified
only by launching it on Windows with an RTX GPU.

## What changes inside the pack

- **Embeddium is required.** Sunlit Valley ships it. Embeddium replaces
  vanilla's chunk builder, so terrain is captured inside Embeddium's meshing
  task (`EmbeddiumMeshingTaskMixin`) while its culling and block-entity
  rendering stay as they are. Embeddium's own GL meshing is skipped while Remix
  renders, because GL terrain is never drawn.
- **Shader mods must go.** Remix replaces the scene, so the mod refuses to load
  beside **Oculus**. **Colorwheel** and **Colorwheel Patcher** require Oculus
  and go with it. `scripts/install.ps1` disables all three for you.
- **Create and other Flywheel users** draw kinetic blocks, belts, contraptions
  and trains with GPU instancing, which Remix cannot see. While Remix renders,
  Flywheel's backend reports itself off, so Create falls back to its ordinary
  block-entity and entity renderers, which the capture handles. Toggling RTX
  reloads the world renderer; Flywheel drops or rebuilds its instances with it.
  Create pre-transforms every vertex it draws on that path, so each rotating
  shaft, cog or sail is a new Remix mesh every frame: a busy factory costs more
  than the same blocks standing still. Create also bakes its own side shading
  into vertex colour, so its machines can look slightly darker than vanilla
  blocks under path tracing.
- **Entity Model Features and Entity Texture Features** keep working: custom
  models and random texture variants are captured. ETF's emissive overlay
  layers are drawn only into ETF's own buffers, so they do not glow in Remix.
- **flerovium** and Embeddium's own entity and particle fast paths step aside
  for the capture, which never presents itself as an Embeddium vertex writer.
- **particlerain** cancels vanilla rain and snow in the pack's configuration,
  so weather reaches Remix as its particles: falling sprites rather than
  stretched streaks, and upright ripples. There is no rain refraction pass then.
- **Gnetum** ships switched off in the pack. Keep it off: its cached-hand path
  would drop the first-person hand from Remix.
- **WATUT**'s player-status particles are drawn after vanilla's particle pass,
  which Remix replaces, so they do not show while Remix renders.
- **ImmediatelyFast**'s map atlas and enlarged font atlas both work, at a memory
  cost: every map change re-uploads the 4096 x 4096 map atlas, and glyph pages
  are 2048 x 2048. Setting `map_atlas_generation` and `font_atlas_resizing` to
  `false` in `config/immediatelyfast.json` restores the Fabric build's costs.

Not ported, because Embeddium or Forge does the job differently or they are
pure performance work: vanilla's visibility walk and its
`rtxremix.vanillaVisibilityWalk` switch, single-player chunk pacing, the client
and server light-snapshot policies, the seeded sky-light scan, the
neighbour re-flag filter, the section-builder and worker-pool caps, and the
allocation-free model-variant and biome-colour mixins. The matching
`rtxremix.*` switches in [Tuning](../docs/TUNING.md) do nothing in this build;
every other switch there applies.

## Multiplayer

Install the mod on the client only to play on a Sunlit Valley server. The
network channel accepts a server without it. For that connection Forge drops
the mod's block, item, menu and particle types from the client registry, so
the Copper Beacon and the water-rapids particles are absent, and block light
rules are re-indexed to the server's block IDs. Path tracing is otherwise
unchanged. A server that installs the mod needs it on every client, because its
registry then contains the Copper Beacon. Zones and world conf layers are saved
into single-player worlds only, as in the Fabric build.

## Build

Requirements are those of the [root build](../docs/GETTING_STARTED.md#prerequisites):
Windows, the Visual Studio C++ tools for the JNI bridge, `JAVA_HOME` pointing
to a 64-bit JDK (17 or newer) for the bridge's JNI headers, and network access
for the pinned runtime. Gradle provisions Java 17 for the mod itself. Run these
from this directory:

```powershell
.\gradlew.bat build
.\gradlew.bat test
.\gradlew.bat package -Parch=win64
```

`build` writes a lightweight JAR to `build/libs/` with MixinExtras nested
inside it. `package` builds the JNI bridge with the shared
`rtxremix-java/native/build_native.bat`, fetches any missing runtime and the
runtime textures through Packman exactly as the root build does, and writes the
distributable JAR to `build/distributions/`. It carries the runtime under
`native/remix-sdk-<arch>/`, plus `native/rtx.conf`, the `native/rtx-remix/`
conf payload, `native/textures/` and the license texts, the same layout
`NativeExtractor` reads in the Fabric build. Omit `-Parch` for a JAR with both
architectures, which needs both runtimes staged from the same kind of source.
Every build is stamped with its time and commit, so a new JAR always re-extracts
the runtime once.

Do not build while a client from this checkout is running.

## Install

Close the instance, then point the script at the instance's game directory
(the folder that holds `mods\`):

```powershell
.\scripts\install.ps1 -GameDirectory 'C:\Prism\instances\Sunlit Valley\minecraft' `
  -Package '.\build\distributions\rtx-remix-forge-1.20.1-<version>-win64.jar'
```

It copies the JAR into `mods\` as `rtx-remix-forge-1.20.1.jar`, removes older
copies, and renames Oculus and the Colorwheel mods to `.jar.disabled`.
`-Uninstall` removes the JAR and re-enables them. On first launch the mod
unpacks `remix-sdk\`, `rtx.conf`, the runtime textures and the `rtx-remix\`
conf payload into the game directory, as described in
[AGENTS.md](../AGENTS.md#runtime-and-install-model).

Keep the pack's own memory setting: Sunlit Valley needs far more heap than the
2 GB the Fabric installer sets for vanilla. The G1 options in
`RECOMMENDED_JVM_ARGS` ([JVM Arguments](../docs/TUNING.md#jvm-arguments)) apply
here too, without the `-Xmx`.

Launch through a Prism or CurseForge instance signed in to a Microsoft account
that owns Minecraft: Java Edition. Commands, keys and settings are those of the
[In-Game Guide](../docs/IN_GAME_GUIDE.md): F8 toggles RTX, G toggles held
lights, Alt+X opens the Remix developer menu, and **Options > RTX Remix
Settings** holds the settings pages.

# Installing CSMC

CSMC is a Fabric mod for Minecraft Java Edition 1.21.1. The repository and the mod contain **no Valve assets**:
you install the mod, then generate models, sounds and data from **your own** Counter-Strike 2 install into a local
cache on your computer (default `~/.csmc/cache`). Until you do, CSMC falls back to vanilla placeholder items and
sounds. Joining a server never gives you its assets; every player exports their own.

This page is the player walkthrough. For running a server see [server.md](server.md); for what the mod does see
[gameplay.md](gameplay.md); for the exporter's internals see [dev/asset-export.md](dev/asset-export.md); release
maintainers use [dev/packaging.md](dev/packaging.md).

## Rules

- **Personal use only.** Exported models, textures, sounds, and copies of `weapons.vdata` or `surfaceproperties_game.txt` are Valve content. The [Steam Subscriber Agreement](https://store.steampowered.com/subscriber_agreement/) limits use to personal, non-commercial purposes.
- **Never commit or share exports.** Do not commit, push, or publish any exported or copied asset. The cache MUST stay outside the repository; the default `~/.csmc/cache` is outside it.

Powered by Source 2 Viewer (see [Acknowledgements](#acknowledgements)).

## Requirements

| Requirement | Notes |
|---|---|
| Minecraft Java Edition `1.21.1` | With your own Microsoft account that owns Java Edition. Never share account or session files. |
| Java runtime `21` | Native to your OS and CPU. A desktop runtime is needed on clients (do not follow the server's headless-JRE instructions). |
| Fabric Loader `0.19.5` | Exact pin. |
| Fabric API `0.116.17+1.21.1` | Exact pin; do not accept a newer recommendation. |
| CSMC JAR | `csmc-0.1.0.jar`. The displayed `0.1.0` does not identify a build: use the exact JAR your server uses. |
| Python 3.9+ | Must already be installed (for example, Homebrew `/opt/homebrew/bin/python3`). Glove and icon export also need **NumPy and Pillow** in the Python environment that runs the exporter. |
| CS2 content files | Your own `game/csgo/pak01_dir.vpk` plus the numbered chunks (`pak01_000.vpk`, ...) it references; `game/core` may supply shared grenade audio. Fetch them with DepotDownloader, see [`docs/dev/cs2-data.md`](dev/cs2-data.md). |
| Source2Viewer-CLI | The matching release/OS/CPU **command-line** executable from [s2v.app](https://s2v.app), not just the GUI; use the release-tested version (`20.1` is the currently documented baseline). Put it on `PATH`, set `CSMC_S2V_CLI`, or pass `--cli`. Framework-dependent builds may need a .NET runtime; follow the official platform release instructions rather than assuming every build is self-contained. |
| ffmpeg (needed for in-game audio) | On `PATH`, with `libvorbis` or FFmpeg's native Vorbis encoder, to convert sounds to `.ogg`. Install with `brew install ffmpeg`. Without a Vorbis encoder, sounds stay `.wav` (MP3 sources use macOS `afconvert` when available) and the exporter warns; WAV/MP3 fallback exports do not supply the OGG playback the mod expects. |
| Cache filesystem | A writable directory **outside the repository** that supports hard links (for example local NTFS, APFS or ext4). Avoid unverified FAT/exFAT, network or cloud-synced folders. Its contents must not contain symlinks, so no output can be redirected back into the repository. |

The exporter does not install dependencies. The CLI tools support multiple operating systems upstream, but this
project's complete Windows/Linux export-and-play workflow has **not** yet been qualified: do not expect one-click
installs there. On macOS pick native Apple Silicon or x64 tools as appropriate.

## Install the mod

### Option A: modpack (`.mrpack` in Prism Launcher)

When a maintainer publishes a release (see [dev/packaging.md](dev/packaging.md); none is published yet), you
download a release folder containing `csmc-RELEASE_ID.mrpack`, `csmc-asset-tools-RELEASE_ID.zip`, `README.txt` and
`SHA256SUMS`. The pack pins Minecraft and Fabric Loader and includes the exact CSMC JAR, so you do not build
anything. Import it into [Prism Launcher](https://prismlauncher.org/download/):

1. Download the two archives from the release link. Check the outer SHA-256 against the trusted announcement **before importing or running scripts**:
   - macOS: `shasum -a 256 /path/to/csmc-RELEASE_ID.mrpack`
   - Linux: `sha256sum /path/to/csmc-RELEASE_ID.mrpack`
   - Windows PowerShell: `Get-FileHash 'C:\Downloads\csmc-RELEASE_ID.mrpack' -Algorithm SHA256`
   Repeat for the asset-tools ZIP. A mismatch means stop and obtain the correct files.
2. Install Prism, then sign into **your own** Microsoft account with Minecraft Java Edition.
3. **Add Instance → Import**, selecting the `.mrpack` file or trusted pack URL. Let Prism fetch its pinned Minecraft/Fabric/dependency files. You do not need the Fabric installer or a JDK/Gradle project to use a Prism instance.
4. In the instance Java settings, select **Java 21**, native to the computer. Prism offers automatic Java management in supported installations; verify the selected runtime instead of relying on the system `java` command. A desktop Java runtime is needed on clients (do not blindly follow the server's headless-JRE instructions).
5. Check Versions shows Minecraft `1.21.1` / Fabric `0.19.5`, and Mods has exactly the intended CSMC JAR and Fabric API `0.116.17+1.21.1`. Use Open Folder to hash the CSMC JAR against the release README if needed. Do not add extra mods before this baseline works.

The pack carries no personal configuration: choose your own volume, controls and viewmodel size.

### Option B: manual Fabric install (build from source)

1. Install Java 21 and the Fabric installer (the server runbook pins installer `1.1.2`). Install Fabric Loader
   `0.19.5` for Minecraft `1.21.1` in your launcher.
2. Build the JAR once, from the `mod/` directory (this example selects Homebrew's Java 21 on macOS; any Java 21 works):

   ```sh
   cd mod
   export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home
   ./gradlew remapJar
   shasum -a 256 build/libs/csmc-0.1.0.jar
   ```

   Use only `mod/build/libs/csmc-0.1.0.jar`: not `-sources`, development or test JARs, and do not select
   `build/libs/*.jar`. Everyone on the same server must use the identical JAR (compare the SHA-256).
3. Put `csmc-0.1.0.jar` and Fabric API `fabric-api-0.116.17+1.21.1.jar` (for example from
   `https://maven.fabricmc.net/net/fabricmc/fabric-api/fabric-api/0.116.17%2B1.21.1/fabric-api-0.116.17%2B1.21.1.jar`)
   into the profile's `mods/` folder. No Sodium, Iris or Mod Menu dependency is required; add optional mods only
   after checking compatibility, and not before this baseline works.
4. Use a real authenticated launcher profile. `./gradlew runClient`, `runLanClient1/2` and fake development
   usernames are development tools, not install methods.

Build, tests and development launch tasks are described in the [README](../README.md#building-from-source).

## Export your CS2 assets

`tools/export-cs2-assets` reads Counter-Strike 2 weapon data, models and sounds from your own local install and writes
them into a local cache that the mod reads at runtime. Run every command from the repository root. If you received a
release folder instead, first check the separately checksummed asset-tools ZIP against the published SHA-256, unzip it,
and run the commands from the extracted folder.

### 1. Prepare Python

```sh
python3 -m venv .venv
. .venv/bin/activate
python -m pip install numpy Pillow  # At release, use the shipped, tested requirements file instead.
```

On Windows PowerShell, create an isolated environment and invoke its Python directly (no activation-policy change
or Unix shebang required):

```powershell
py -3 -m venv .venv
$Python = Join-Path $PWD '.venv\Scripts\python.exe'
& $Python -m pip install numpy Pillow  # At release, use the shipped, tested requirements file instead.
```

### 2. Run a first export

Focused export of four weapons:

```sh
python3 tools/export-cs2-assets --weapons ak47,glock,awp,m4a1
```

All 36 registered firearms, including the taser (`--weapons` defaults to `all`; grenades, knives, C4 and healthshots are excluded):

```sh
python3 tools/export-cs2-assets
```

Explicit tool, VPK and cache paths:

```sh
python3 tools/export-cs2-assets \
  --vpk ~/csmc-assets/cs2/game/csgo/pak01_dir.vpk \
  --cli ~/csmc-assets/tools/s2v/Source2Viewer-CLI \
  --cache ~/.csmc/cache \
  --weapons ak47,glock,awp,m4a1
```

| Option | Default | Meaning |
|---|---|---|
| `--vpk` | `~/csmc-assets/cs2/game/csgo/pak01_dir.vpk` | VPK index to read. |
| `--cli` | `CSMC_S2V_CLI`, then `Source2Viewer-CLI` on `PATH`, then `~/csmc-assets/tools/s2v/Source2Viewer-CLI` | Source2Viewer-CLI binary. |
| `--cache` | `CSMC_CACHE`, then `~/.csmc/cache` | Cache root. |
| `--weapons` | `all` | Comma- or space-separated firearm ids; `all` exports all firearm entries, including the taser. |

Weapon ids are the CS2 internal names. Two are easy to confuse:

- `m4a1` is the **M4A4**.
- `m4a1_silencer` is the **M4A1-S**.

Exit status is nonzero if any requested weapon is incomplete. The exporter still processes the remaining weapons before exiting.

### Export modes

The first run above exports firearms. The other content types are **separate modes**; do not combine `--arms`,
`--gloves`, `--knives`, `--grenades`, `--equipment`, `--skins`, `--icons`, `--sounds` or `--gun-clips` in one invocation. Run them in
this order (the full sequence for a complete export; modes later in the list read records written by earlier ones):

```sh
export CSMC_S2V_CLI="/absolute/path/to/Source2Viewer-CLI"
export CSMC_CACHE="$HOME/.csmc/cache-RELEASE_ID"      # optional; see "Choosing the cache location"
VPK="/absolute/path/to/game/csgo/pak01_dir.vpk"
python3 tools/export-cs2-assets --vpk "$VPK"            # firearms (includes viewmodel clips)
python3 tools/export-cs2-assets --vpk "$VPK" --knives
python3 tools/export-cs2-assets --vpk "$VPK" --arms     # after --knives
python3 tools/export-cs2-assets --vpk "$VPK" --skins
python3 tools/export-cs2-assets --vpk "$VPK" --gloves   # after --skins and --knives; needs numpy and Pillow
python3 tools/export-cs2-assets --vpk "$VPK" --grenades
python3 tools/export-cs2-assets --vpk "$VPK" --equipment
python3 tools/export-cs2-assets --vpk "$VPK" --icons    # last: icons cover every exported record; needs numpy and Pillow
python3 tools/cs2_case.py --vpk "$VPK"
python3 tools/cs2_case.py --vpk "$VPK" --case crate_community_4 --item inventory_weapon_case
```

For shared grenade audio add `--extra-vpk /absolute/path/to/game/core/pak01_dir.vpk` to the grenade command when
available, with its chunks present too. `--gun-clips` is the separate clip-refresh mode if needed (firearm export
already includes the clips). A fresh cache plus the exporter of the same revision avoids mixing old and new
animation/catalog formats. What each mode writes, and its manifest records, is in
[dev/asset-export.md](dev/asset-export.md).

For Windows PowerShell, the equivalent calls use the isolated interpreter:

```powershell
$env:CSMC_S2V_CLI = 'C:\Tools\Source2Viewer\Source2Viewer-CLI.exe'
$env:CSMC_CACHE = "$HOME\.csmc\cache-RELEASE_ID"
$Vpk = 'D:\SteamLibrary\steamapps\common\Counter-Strike Global Offensive\game\csgo\pak01_dir.vpk'
& $Python tools/export-cs2-assets --vpk $Vpk
& $Python tools/export-cs2-assets --vpk $Vpk --gloves
# Repeat the separate --knives, --arms, --skins, --grenades, --equipment and --icons modes and the cs2_case.py commands above with & $Python.
```

Replace `RELEASE_ID` with the release you are exporting for (or any name), and the executable and archive paths with
your own.

### What is written

Under the cache root:

- `data/weapons.vdata`: decompiled KV3 text, and `data/surfaceproperties_game.txt`.
- `models/<id>/<id>.gltf`: the world model, with `.bin` and PNG sidecars. Self-contained. If a weapon has only a viewmodel, that becomes the primary `<id>.gltf`.
- `models/<id>/<id>_v.gltf`: the viewmodel. Written only when `weapons.vdata` defines a viewmodel distinct from the world model.
- `sounds/<id>/fire`, `draw`, `reload_part` (with optional `_variant` suffixes): `.ogg` when ffmpeg is available, otherwise `.wav`.
- `manifest.json`: per-weapon status/errors, source paths, timings, tool version, model files and clip names. `sounds/<id>/manifest.json` maps exact Source 2 sound events and fire/reload/draw action groups to files.

Models are exported as glTF with materials, animations and textures. The exporter validates the output: the only supported required glTF extension is `KHR_mesh_quantization`, and each vertex may have at most 4 joint influences.

### Choosing the cache location

Cache root precedence for the mod: Java system property `csmc.cache`, then `CSMC_CACHE`, then `~/.csmc/cache`. To point the game at another root, pass the property when launching the Java process:

```sh
JAVA_TOOL_OPTIONS="-Dcsmc.cache=/absolute/path/to/cache"
```

In a launcher such as Prism, set the property in the **instance JVM arguments**: `-Dcsmc.cache=/absolute/path/to/your/cache-RELEASE_ID`.
On Windows use a quoted argument such as `"-Dcsmc.cache=C:\Users\YourName\.csmc\cache-RELEASE_ID"` when the path
contains spaces. Do not assume a GUI launcher inherits terminal environment variables. The default, if no override is
used, is your own `~/.csmc/cache`.

### Check the result

Check exporter results, not just directory existence. Missing VPK chunks or failed required models/textures/audio must be resolved before claiming a complete export. The currently documented M14 asset is absent from the inspected CS2 content; treat it as a known placeholder limitation, not an excuse to ignore unrelated failures. After a CS2 update, rerun the relevant modes; after changing the export while the game runs, F3+T reloads resources (a full client restart is the safe choice after catalog changes).

### Refreshing

- With the same VPK and the same Source2Viewer-CLI, a successful export produces the same output. Re-running is safe.
- To force a refresh of one weapon, delete its entries (`models/<id>/` and `sounds/<id>/`) under the cache root, then re-run with `--weapons <id>`.
- To refresh the data files, delete `data/weapons.vdata` and `data/surfaceproperties_game.txt`, then re-run.
- To refresh skins after a CS2 update, rerun `--skins`; unchanged decoded textures are reused.
- To add skin shine to a cache exported before shine existed, run `python3 tools/cs2_skins.py --shine` (add `--cache <dir>` for a non-default cache). It writes only `skins/**/shine.json` and `skins/*/_default/shine_mask_*.png` (about 20 seconds); then press F3+T or restart the client.
- To add the AWP and SSG 08 scope sounds to an existing cache, run `python3 tools/export-cs2-assets --sounds --weapons awp,ssg08`.

## Join and verify

Launch your instance, add the server address from the server owner or release README, and join after the owner
allowlists your actual Java username. Keep online authentication enabled. See [server.md](server.md) for how servers
are hosted; never use `localhost` for someone else's server.

Every player needs the same Minecraft/Loader/API pins and the **exact** CSMC JAR as the server, a real authenticated
profile, and their own complete local export. The assets are local to each client: no port forwarding fixes a missing
local model, glove texture, skin input or stale catalog. Existing clients are not guaranteed to have matching assets
merely because they connect successfully; fully restart clients after changing the export or catalog unless the
relevant reload path is verified for that release.

Check model, arms, skin and sound loading, inspect/reload/scope behavior, and magazine/reserve conservation with
another real client. Open **Options → Gun Viewmodel...** for local size/position preferences ([gameplay.md](gameplay.md)).
Cache processing can take time on first launch; a successful connection alone does not prove all art is loaded.

## Updating and troubleshooting

Updates ship as a new unique pack/tools release. The safest upgrade is importing the new pack as a **separate Prism
instance**; keep the old instance and cache until the new one is accepted, and keep a separate cache per incompatible
exporter/content release, selected through the instance JVM arguments. Never delete the previous cache to force an
upgrade. Do not treat the pack as storage for your personal config: instance re-import can remove and reapply
previous overrides. If the network protocol or schema format changed, the server and all clients must change together.

| Symptom | First checks |
|---|---|
| Connection refused | Correct remote endpoint, server running, tailnet permission or public forwarding; not localhost |
| Authentication/allowlist failure | Real signed-in Java account; owner has allowlisted its actual username; don't disable online-mode |
| Missing mixin/class, incompatible payload, disconnect at join | Same CSMC hash and exact Loader/API/Minecraft pins; remove stale duplicate JARs |
| Placeholder guns/arms/skins | Correct cache JVM argument, complete owner-local export, matching catalog/manifest/sidecars; client restart after catalog changes |
| No CS sounds | ffmpeg available during export, actual OGG files generated, client volume settings |
| Script import or chunk errors | All frozen sibling helpers included; index plus required numbered/core VPK chunks present |
| A pack imports but features differ | Pack/JAR hash, server metadata snapshot and local export compatibility; version label alone is insufficient |

Exporter problems:

- **Missing numbered chunk (`pak01_NNN.vpk`).** `pak01_dir.vpk` is only the index. Its referenced chunks must sit beside it in `game/csgo/`. Download missing chunks with DepotDownloader (see [`docs/dev/cs2-data.md`](dev/cs2-data.md)), then re-run. Weapon materials can depend on chunks outside weapon-only download prefixes. Failed weapons are retried; others remain cached.
- **Source2Viewer-CLI not found.** Pass `--cli`, or set `CSMC_S2V_CLI`, or add the binary to `PATH`.
- **No model field / empty model.** Some vdata entries describe unreleased weapons (for example `m14`) with an empty `m_szWorldModel`. These are reported as failures rather than replaced with an unrelated weapon model.
- **Sounds are `.wav`.** ffmpeg is missing. Run `brew install ffmpeg` and re-run after deleting the weapon's `sounds/<id>/`.
- **Extension error.** The model requires a glTF extension other than `KHR_mesh_quantization`. The manifest records the error for that weapon.
- **Exit status nonzero.** Read the manifest for the per-weapon errors; fix and re-run with `--weapons` for the failed ids.
- **Skin unavailable:** inspect `skins/<weapon>/<kit>/params.json` and `skins/missing.json`, download chunks listed in `skins/filelist-missing.txt`, then rerun `--skins`. A leftover `skins/.decode-*` directory may be removed only when no exporter is running.

For support, send the release ID, OS/CPU, Java version, a sanitized launcher/game log and screenshots. Logs can contain
usernames, local paths, IPs and authentication data; inspect before sharing. Never send account databases, access tokens,
full launcher folders or Steam credentials. Exported models, textures, sounds and screenshots of exported content are
Valve content: do not commit or publish them.

## Acknowledgements

Powered by Source 2 Viewer (Source2Viewer-CLI / ValveResourceFormat). Sources:
[command line](https://s2v.app/ValveResourceFormat/guides/command-line.html),
[exporting models](https://s2v.app/ValveResourceFormat/guides/exporting-models.html),
[exporting sounds](https://s2v.app/ValveResourceFormat/guides/exporting-sounds.html).

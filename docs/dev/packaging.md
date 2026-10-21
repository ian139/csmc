# Release packaging (maintainers)

**Future release instructions, not a published release.** Do this after the maintainer finishes, reviews and freezes the intended features. No pack, installer, public download or final artifact hashes have been created by writing this document.

Goal: friends import one versioned pack, use the same CSMC binary/dependencies as the server, prepare their own local CS2 assets, and join with their own Minecraft Java accounts. The friends' side of the procedure is [install.md](../install.md); hosting and networking are covered separately in [server.md](../server.md).

**Required friend platforms: Windows, Linux, and macOS.** One identical CSMC JAR/modpack serves all three; Java and asset-export executables must match each friend's OS and CPU architecture. All three platforms are release requirements, not optional future support.

## 1. What friends download

Use a **Modrinth-format `.mrpack`, imported into Prism Launcher**. It pins Minecraft and Fabric Loader and records dependency download hashes. The unpublished CSMC JAR goes inside `overrides/mods/`, so friends do not build it themselves. A Modrinth account or public Modrinth listing is not necessary to share a local `.mrpack` privately.

Publish a release folder containing only:

```text
csmc-RELEASE_ID.mrpack
csmc-asset-tools-RELEASE_ID.zip
README.txt
SHA256SUMS
```

`RELEASE_ID` must be unique for every changed pack, even while the internal mod version remains `0.1.0`. Use a version plus reviewed build/revision suffix. Never silently replace a release at the same name/URL. Include the CSMC source revision/artifact identity, exact pins, JAR SHA-256 and exporter version/checksums in the README or release manifest.

The public/private pack distinction does not grant extra content rights. Do not ship Minecraft game JARs, Valve models/textures/sounds/catalogs, your Steam content or credentials, or your personal launcher/game directory. Clients' asset exports stay local. Review your own code license and all third-party distribution terms before sharing; private sharing does not bypass third-party terms. Keep Minecraft's required unofficial-product disclaimer in the release notes.

## 2. Freeze the exact software

Current source pins; recheck `mod/gradle.properties` at the final release:

| Component | Required version / artifact |
|---|---|
| Minecraft Java Edition | `1.21.1` |
| Java runtime | Major `21`, native to the friend's OS/CPU |
| Fabric Loader | `0.19.5` |
| Fabric API | `0.116.17+1.21.1` |
| CSMC | Frozen `mod/build/libs/csmc-0.1.0.jar` |
| Exporter/tooling | Matching frozen exporter scripts; Source2Viewer CLI `20.1` is the currently documented baseline |

The dedicated server needs the same CSMC JAR and compatible pinned Loader/API, not the `.mrpack` launcher profile. See [server.md](../server.md). Do not include JUnit, GameTest, Loom, Gradle, development dependencies, or server/world files in the client pack. No Sodium/Iris/Mod Menu dependency is currently required; add optional mods only after explicit compatibility checks and updating the lock.

At release freeze, the maintainer builds the distributable **once**, from the repository root:

```sh
(
set -eu
cd mod
export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home
./gradlew remapJar
shasum -a 256 build/libs/csmc-0.1.0.jar
)
```

Distribute that exact file to the pack and server. Do not rebuild separately for each friend, select `build/libs/*.jar`, or use `-sources`, development or test JARs. Dependency ranges in `fabric.mod.json` are broader than these exact pins and do not enforce release equality. The release manifest/hash is the authority; `0.1.0` displayed in-game is not sufficient.

## 3. Create a clean authoring instance in Prism

1. Install a stable [Prism Launcher](https://prismlauncher.org/download/) release, record its version, and use it for the pack qualification check. No instance export has been exercised for this project yet.
2. Create a **new** instance: Minecraft `1.21.1`, Fabric Loader `0.19.5`. Select native Java 21 in the instance's Java settings. Do not clone `mod/run`, `mod/run-client2` or your personal Minecraft folder.
3. In the instance editor's Mods page, use the Modrinth download browser to add **Fabric API `0.116.17+1.21.1`**. Verify the selected game/version rather than accepting a newer recommendation.
4. Add the one frozen `csmc-0.1.0.jar` manually. This custom mod need not be uploaded anywhere to be included as an override.
5. Initially distribute **no personal configuration**. Let each friend choose volume, controls and viewmodel size. If a shared config is later needed, whitelist its exact paths and check it for local cache paths/debug settings first.
6. Prepare assets locally for the author's own qualification run (section 6), but keep them outside the instance/export. Do not give the pack an absolute path into the author's home directory.

### Export the `.mrpack`

Use Prism's **Export Instance** dialog and select the **Modrinth format** option. Set name, unique release/version ID and summary. Labels may differ with Prism versions; this workflow comes from upstream export source and must be checked against the stable application used for release.

Inspect the selection tree, not just the default exclusions. Export only the custom mod and explicitly approved configuration. Known Modrinth dependencies such as Fabric API should become hash-pinned downloads in the manifest rather than bundled third-party JARs. The local/unpublished CSMC JAR should become an override.

Explicitly exclude:

- `saves/`, world/server folders, datapacks from a personal world, player inventories and server operator lists.
- `options.txt`, `servers.dat`, launcher/account/session data, tokens, usernames/profile shortcuts and personal screenshots.
- Logs, crash reports, debug traces, caches, `.gradle`, `.fabric`, `__pycache__`, `.pyc` files and tool binaries.
- `.csmc/`, any CS2 export/cache, `manifest.json` from that cache, `skins/`, `models/`, `sounds/` and `.composited/` exports.
- Development JVM properties/environment (especially `csmc.devSkin`, screenshot/preload flags), absolute author cache paths, Gradle launch scripts and fake `CsmcPlayer1/2` launch identities.

Do not assume `saves/` is automatically excluded. Never export the launcher data directory containing accounts. Keep the exporter tools in their separate allowlisted ZIP, not among Minecraft instance overrides.

Expected structure (schematic, not a ready-to-use manifest):

```text
csmc-RELEASE_ID.mrpack  [ZIP]
  modrinth.index.json
  overrides/
    mods/
      csmc-0.1.0.jar
```

The index must use `formatVersion: 1`, `game: minecraft`, a unique `versionId`, exact dependencies `minecraft: 1.21.1` and `fabric-loader: 0.19.5`. Its Fabric API `files` entry must contain the correct relative `mods/...jar` path, HTTPS download URL, byte length, and **both SHA-1 and SHA-512** hashes. The pack's outer SHA-256 and the CSMC override's separately recorded SHA-256 cover the custom binary too. Do not hand-invent hashes or assume arbitrary download hosts are accepted; let Prism resolve known Modrinth files to their CDN metadata.

## 4. Package only the exporter code friends need

The mod synchronizes gameplay and skin identifiers, **not game assets**. Friends need the matching exporter revision and instructions to generate assets from their own lawfully accessible CS2 files.

Build a separate ZIP from a new, empty staging directory. The commands below are future-only and intentionally copy named code/docs, never the game cache:

```sh
(
set -eu
cd "$(git rev-parse --show-toplevel)"
STAGE="$HOME/csmc-asset-tools-stage"
OUT="$HOME/csmc-asset-tools-RELEASE_ID.zip"  # Set the actual release ID before running.
test ! -e "$STAGE"
test ! -e "$OUT"
mkdir -p "$STAGE/tools" "$STAGE/docs/dev"
cp tools/export-cs2-assets tools/cs2_arms.py tools/cs2_guns.py \
  tools/cs2_knives.py tools/cs2_grenades.py tools/cs2_skins.py \
  tools/cs2_case.py tools/cs2_gloves.py tools/cs2_icons.py tools/cs2_equipment.py "$STAGE/tools/"
cp docs/install.md "$STAGE/docs/"
cp docs/dev/asset-export.md docs/dev/cs2-data.md "$STAGE/docs/dev/"
# Add required license/attribution files for this frozen code before archiving.
# Recheck imports after glove or other exporter changes: add any new required helper modules explicitly.
cd "$STAGE"
shasum -a 256 tools/* docs/*.md docs/dev/*.md > SHA256SUMS
python3 -m zipfile -c "$OUT" tools docs SHA256SUMS
)
```

This is a packaging recipe, not an existing release-builder or automatic installer. At freeze, make the helper allowlist complete for the actual exporter, including `cs2_gloves.py`. Missing helper imports are a release blocker. Pin the release-tested NumPy and Pillow versions in an exporter requirements file, include and checksum it in this ZIP, and use that file for friends' dependency installation. The commands below show the current dependency names, not a completed dependency lock. Include applicable project/third-party notices; Source2Viewer's attribution requirement is documented in [install.md](../install.md#acknowledgements). Prefer links to upstream Python/ffmpeg/Source2Viewer downloads over redistributing unreviewed platform-specific binaries.

Create the final release-folder `SHA256SUMS` **after** the pack, tools ZIP and README are final. On macOS, for example, run `shasum -a 256` on those three explicitly named files. Publish the expected archive checksums through the trusted release announcement as well; a checksum only inside a replaceable archive does not authenticate the download.

## 5. What to send friends

Send one trusted release-folder link (a private release/download location is sufficient), plus:

- Exact release ID and outer SHA-256 for both archives.
- Prism download link and Java 21 requirement; choose ARM64 versus x64 appropriately.
- Own Minecraft Java entitlement/Microsoft account required. Never share your account/session files.
- Own lawful CS2 file access and local asset export required; installing just the mod does not include CS models, animations, gloves, skins or sounds.
- Server address and allowlist instructions. A private endpoint looks like `<server>.<tailnet>.ts.net:25565` and requires authorized Tailscale access; deploying it is covered by [server.md](../server.md), not by this guide. For non-Tailscale friends, send the public endpoint only after [server.md](../server.md)'s external connectivity check succeeds. Never tell friends to use `localhost` for your remote server.

No passwords, Steam tokens, SSH keys or authentication URLs belong in the README or pack. You can use a private file host/GitHub release where appropriate, but do not publish a public pack until content rights/notices are reviewed.

## 6. Friend installation checklist

Friends follow [install.md](../install.md): import the identical modpack (verifying the outer SHA-256 **before importing or running scripts**), generate their local CS2 assets with the matching exporter, and join and verify. Its requirements table, platform notes, Prism import steps, export commands (macOS/Linux and Windows PowerShell), JVM-argument cache selection and symptom/troubleshooting table are the authoritative friend-facing copy; keep them in sync with the release pins above. Do not advertise Windows or Linux as one-click installs until their release checks (section 7) pass.

## 7. Release qualification before sharing

Use a clean imported instance, not the author's Gradle client:

- Inspect `.mrpack` with a ZIP reader: only the intended mod/config overrides; exact version/hash-pinned dependencies; no accounts, worlds, private exports, machine paths or debug flags.
- Confirm CSMC override SHA-256 matches the server and release manifest; confirm Fabric API metadata/version and dependency hashes match the pinned file. Test checksum mismatch rejection rather than bypassing errors.
- Import with the stable Prism version recorded for release and launch with Java 21. Verify no missing class/mixin or network payload errors against the frozen dedicated server.
- Extract the asset-tools ZIP in a fresh folder and ensure all sibling Python imports resolve. Before calling the release plug-and-play, verify clean pack import, local export, audio, cache selection and actual server play on **Windows, Linux, and macOS**, using the friends' actual CPU architectures. One successful Mac run does not satisfy this gate.
- Freeze the private CS2 source build/tool/export compatibility expectations. Identical scripts do not imply byte-identical exports: source updates, path/mtime fingerprints and tool versions can differ. Do not hash whole friends' manifests and expect equality. Compare required catalog IDs/schema/asset coverage instead, and keep server metadata compatible with client exports.
- Recheck new glove/movement dependencies and any added scripts after integration. This guide records current evidence, not an assurance that unfinished features need no packaging changes.
- Review licenses/notices for every bundled file and referenced mod; don't ship unlicensed assets because a launcher can import them.

## 8. Updates and rollback

Ship a new unique pack/tools release and checksums for every protocol/schema/code update. Keep old files available for deliberate rollback. The safest upgrade is importing the new pack as a **separate Prism instance**; preserve the old instance/cache until the new one is accepted. Change the server and all clients together if the network protocol or schema format changed. Avoid blindly overwriting friends' personal controls, volume or viewmodel settings.

Maintain a separate cache per incompatible exporter/content release and select it through instance JVM arguments. Never delete the previous cache to force an upgrade. Instance re-import can remove/reapply previous overrides, so do not treat the pack as storage for a friend's personal config. Restore compatible old JARs/export schema and server snapshot together when rolling back; see [server.md](../server.md#9-backups-upgrades-and-rollback).

For support, send release ID, OS/CPU, Java version, a sanitized launcher/game log and screenshots. Logs can contain usernames, local paths, IPs and authentication data; inspect before sharing. Never send account databases, access tokens, full launcher folders or Steam credentials.

## Status and sources

No modpack builder, friend installer or cross-platform setup automation currently exists. This document's export/import/build commands have not been run as a release. Final binaries, hashes, Windows/Linux qualification and a public download URL remain release-time work.

- [Prism downloads](https://prismlauncher.org/download/), [modpack import](https://prismlauncher.org/wiki/getting-started/download-modpacks/), [Import tab](https://prismlauncher.org/wiki/help-pages/zip-import/)
- [Prism Java installation](https://prismlauncher.org/wiki/getting-started/installing-java/), [Java settings](https://prismlauncher.org/wiki/help-pages/java-settings/)
- [Modrinth pack format](https://support.modrinth.com/en/articles/8802351-modrinth-modpack-format-mrpack), [modpack permissions](https://support.modrinth.com/en/articles/8797527-obtaining-modpack-permissions)
- [Prism Modrinth exporter source](https://github.com/PrismLauncher/PrismLauncher/blob/develop/launcher/modplatform/modrinth/ModrinthPackExportTask.cpp), [export selection source](https://github.com/PrismLauncher/PrismLauncher/blob/develop/launcher/ui/dialogs/ExportInstanceDialog.cpp); confirm behavior against the chosen stable app, not just develop.
- [Source2Viewer CLI](https://s2v.app/ValveResourceFormat/guides/command-line.html), [Steam Subscriber Agreement](https://store.steampowered.com/subscriber_agreement/), [Minecraft Usage Guidelines](https://www.minecraft.net/en-us/usage-guidelines)
- Project: `mod/gradle.properties`, `mod/build.gradle`, `CachePaths.java`, `tools/export-cs2-assets` and its sibling helpers; [install.md](../install.md), [server.md](../server.md), [asset-export.md](asset-export.md).

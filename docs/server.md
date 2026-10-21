# Hosting a CSMC server

A runbook for hosting CSMC on a Linux machine: a Fabric 1.21.1 dedicated server that friends reach over Tailscale
and, optionally, through router port forwarding. Players install the mod and export their own assets as described in
[install.md](install.md); the arenas that run on a server are in [arenas.md](arenas.md).

**Status: preparation only; nothing in this guide has been deployed.** The reference host was inspected read-only on
2026-10-19. Commands below are for the eventual deployment after the release is frozen, not instructions to run while
gameplay is still being integrated. Read each command before running it and adapt the placeholders to your own host.

## 1. Reference host and decisions to make

The table lists what you need to know about your host before starting. Placeholders used throughout: `<server>.<tailnet>.ts.net` is the host's MagicDNS name, `<tailscale-ip>` its Tailscale IPv4, `<user>` the SSH user, `<lan-interface>` and `<server-lan-ip>` the LAN interface and address, and `<router-ip>` the gateway.

| Item | Value / requirement |
|---|---|
| Server | `<server>.<tailnet>.ts.net` |
| Tailscale IPv4 | `<tailscale-ip>` |
| Working SSH route | `ssh -o StrictHostKeyChecking=yes <user>@<server>.<tailnet>.ts.net` |
| OS | 64-bit Linux with systemd (this guide uses Ubuntu commands) |
| CPU / RAM | 4+ cores; 8 GiB+ RAM recommended (the server runs with `-Xmx4G`) |
| Storage | A few GB free for the server, world and backups |
| LAN interface / address | Wi-Fi `<lan-interface>`, DHCP `<server-lan-ip>/24` |
| Gateway | `<router-ip>` |
| Game port | Java Edition **TCP 25565**, unoccupied |
| Host firewall | Check your firewall (UFW etc.) before opening anything |
| Java | Java 21 (for example `openjdk-21-jre-headless` from APT, or a Temurin 21 JRE) |
| Existing workloads | Do not replace the firewall, services or storage of anything already running on the host |

Reach the host over SSH with its MagicDNS hostname and an existing known-host entry. Do not disable host-key checks or substitute an untrusted IP-key pairing. Tailscale SSH can request a browser reauthentication check; complete that normally if requested. Administrative setup needs interactive sudo; do not assume passwordless sudo or modify existing privileged wrappers.

Before executing this runbook, fill in:

- Final reviewed CSMC JAR and checksum, with matching client pack. Version `0.1.0` alone does not identify a build.
- New production world versus a migrated development world; see section 8 for inventory/identity risks.
- Real authenticated Minecraft usernames for allowlisting and the owner operator.
- Router ownership/admin access, router model, and WAN address. These were **not** inspected. Do not assume access to the router merely because the server is reachable over Tailscale.
- DHCP reservation, public DNS/DDNS name if wanted, and confirmation that the ISP accepts inbound traffic.

The server currently uses Wi-Fi. Prefer Ethernet for hosting; if switching interfaces, recheck LAN address and update the reservation/forwarding destination.

## 2. Connection model

- **Administration:** SSH over Tailscale only. No router forwarding for SSH, RCON, Docker, databases, or monitoring.
- **Private play:** friends on an authorized tailnet connection use `<server>.<tailnet>.ts.net:25565` or `<tailscale-ip>:25565`. No router forwarding is needed. Tailnet access rules must permit game traffic; MagicDNS alone does not grant access.
- **Public play:** friends without Tailscale use the router's public IP/DNS on TCP 25565, forwarded to the server's reserved LAN address. Online authentication and an allowlist remain enabled.

Tailscale addresses (`100.64.0.0/10`) are overlay addresses: **do not use `<tailscale-ip>` as the router's forwarding destination**. Do not enable Funnel, UPnP, DMZ hosting, subnet routing, exit nodes, or IP forwarding for this setup. This is a Java/Fabric server, not Bedrock; no UDP 19132 or UDP 25565 rule is required with query disabled.

## 3. Freeze and stage the release on the build machine

Current source pins, to recheck at release:

| Component | Pin |
|---|---|
| Minecraft | `1.21.1` |
| Java | `21` |
| Fabric Loader | `0.19.5` |
| Fabric API | `0.116.17+1.21.1` |
| CSMC artifact | `mod/build/libs/csmc-0.1.0.jar` |
| Fabric installer | `1.1.2` |

Use the exact same reviewed CSMC JAR and compatible pinned dependencies on server and clients. Do not select artifacts with `*.jar`: source/dev/GameTest JARs are not production mods. Gradle and `runLanServer` are development tools, not a production service.

Only after the source is frozen, build the distributable and prepare a **private** staging directory:

```sh
cd mod   # from the repository root
export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home
./gradlew remapJar

RELEASE="$HOME/csmc-server-release"
mkdir -p "$RELEASE/mods" "$RELEASE/cache/data" "$RELEASE/cache/skins"
cp build/libs/csmc-0.1.0.jar "$RELEASE/mods/"
curl -fL 'https://maven.fabricmc.net/net/fabricmc/fabric-api/fabric-api/0.116.17%2B1.21.1/fabric-api-0.116.17%2B1.21.1.jar' \
  -o "$RELEASE/mods/fabric-api-0.116.17+1.21.1.jar"
curl -fL 'https://maven.fabricmc.net/net/fabricmc/fabric-installer/1.1.2/fabric-installer-1.1.2.jar' \
  -o "$RELEASE/fabric-installer-1.1.2.jar"

cp "$HOME/.csmc/cache/data/weapons.vdata" "$RELEASE/cache/data/"
cp "$HOME/.csmc/cache/data/surfaceproperties_game.txt" "$RELEASE/cache/data/"
cp "$HOME/.csmc/cache/skins/index.json" "$RELEASE/cache/skins/"
cd "$RELEASE"
shasum -a 256 mods/*.jar fabric-installer-1.1.2.jar \
  cache/data/weapons.vdata cache/data/surfaceproperties_game.txt cache/skins/index.json > SHA256SUMS
```

Record the reviewed source commit/artifact identity with the release; checksum verification confirms transfer integrity, not that an arbitrary locally built JAR was reviewed. Future dependency bumps require refreshed pins and verification, not selecting `latest` during installation.

### Cache split

The dedicated server needs these three private metadata exports to reproduce the current game:

- `data/weapons.vdata`: CS weapon tuning; missing file uses fallback numbers.
- `data/surfaceproperties_game.txt`: penetration modifiers; missing file uses authored fallbacks.
- `skins/index.json`: case pools, names, compatibility, rarity. Without it, catalog-backed case rolls have no eligible pool.

It does **not** need models, textures, sounds, arms, PNGs, or client `.composited` thumbnails. Clients need their own complete matching export, including `manifest.json`, model/arm/glove assets, sound manifests/files, skin inputs/index, and case artwork. See [install.md](install.md). Existing clients are not guaranteed to have matching assets merely because they connect successfully.

Keep Valve exports private and outside the repository/public mod downloads. These instructions are not permission to redistribute game assets. Re-evaluate this split whenever glove, movement or other features change what the server reads from the cache; it was last inspected before those integrations.

## 4. Prepare the Linux host (interactive SSH)

First recheck resources and current services; observed addresses/capacity can change:

```sh
ssh -o StrictHostKeyChecking=yes <user>@<server>.<tailnet>.ts.net
hostname
cat /etc/os-release
free -h
df -h /
ip -4 -brief address
ip route show default
ss -ltn 'sport = :25565'
systemctl --failed --no-pager
sudo ufw status verbose
```

No host changes are needed just to read this guide. At deployment, inspect the existing firewall/Tailscale policy before changing it. Do not run `ufw reset`, flush nftables/iptables, restart Docker, or modify monitoring services.

For a **fresh** CSMC installation only (if these paths already contain an installation, use the upgrade procedure instead):

```sh
sudo apt update
sudo apt install openjdk-21-jre-headless
/usr/bin/java -version
id csmc || sudo useradd --system --user-group --home-dir /srv/csmc --shell /usr/sbin/nologin csmc
sudo install -d -o csmc -g csmc -m 0750 /srv/csmc/server /srv/csmc/cache /srv/csmc/backups
```

Confirm Java reports major version 21. Start with `-Xms1G -Xmx4G`, view distance 8, simulation distance 6, and 8 player slots as conservative initial settings, **not measured capacity guarantees**. The host already uses swap and runs monitoring; don't allocate its entire available memory to Java. Tune using actual tick times and memory, not just the CPU core count.

From the **build machine**, copy the private release into the SSH user's staging area (not directly into the live service):

```sh
scp -r "$HOME/csmc-server-release" <user>@<server>.<tailnet>.ts.net:~/
```

Back on the **server**, verify and install:

```sh
(
set -eu  # Abort the whole install block on checksum, transfer, ownership, or installer failure.
cd ~/csmc-server-release
sha256sum -c SHA256SUMS
sudo cp -a mods /srv/csmc/server/
sudo cp fabric-installer-1.1.2.jar /srv/csmc/server/
sudo cp -a cache/. /srv/csmc/cache/
sudo chown -R csmc:csmc /srv/csmc/server /srv/csmc/cache
sudo -u csmc /usr/bin/java -jar /srv/csmc/server/fabric-installer-1.1.2.jar server \
  -mcversion 1.21.1 -loader 0.19.5 -downloadMinecraft -dir /srv/csmc/server
)
```

Keep the generated `libraries/`, `server.jar`, launcher properties and `fabric-server-launch.jar` together. The launcher alone is not a complete installation. Verify only the intended CSMC release and Fabric API are under `mods/`; no development/test/client-only mods.

Review [the Minecraft EULA](https://www.minecraft.net/eula) and, only if accepted for this server, write `/srv/csmc/server/eula.txt` containing `eula=true` as the service user. Do not reuse development EULA consent silently.

## 5. Production properties, initial login, and service

Use `sudo -u csmc nano /srv/csmc/server/server.properties` to create the following initial properties. A blank `server-ip` allows both Tailscale and router-forwarded LAN traffic; the firewall/allowlist control access.

```properties
server-ip=
server-port=25565
online-mode=true
enforce-secure-profile=true
white-list=true
enforce-whitelist=true
enable-rcon=false
enable-query=false
level-name=world
motd=CSMC private server
max-players=8
view-distance=8
simulation-distance=6
spawn-protection=0
enable-command-block=false
```

For an intentionally migrated arena world, use its directory name for `level-name` and enable command blocks only if the reviewed arena datapacks require them. Do not copy development `server.properties`, `ops.json`, `usercache.json`, or `whitelist.json` wholesale: those use offline developer identities and unsafe public-server settings.

Start once in the foreground from SSH **before enabling the service**:

```sh
sudo -u csmc sh -c 'cd /srv/csmc/server && exec env -u JAVA_TOOL_OPTIONS -u _JAVA_OPTIONS -u JDK_JAVA_OPTIONS /usr/bin/java -Xms1G -Xmx4G -Dcsmc.cache=/srv/csmc/cache -jar fabric-server-launch.jar nogui'
```

Wait for `Done`. In that **Minecraft console**, run `whitelist add YOUR_REAL_JAVA_USERNAME` for each actual account, and `op YOUR_REAL_JAVA_USERNAME` for the owner only. Replace those names before use; `CsmcPlayer1/2` are development profiles, not verified production accounts. Join once through Tailscale using the authenticated Fabric client, check logs/cache imports, then type `stop` and wait for all dimensions to save. A failed/disconnected SSH session is not evidence of a successful save.

Create `/etc/systemd/system/csmc.service` using `sudoedit`:

```ini
[Unit]
Description=CSMC Fabric Minecraft server
Wants=network-online.target
After=network-online.target

[Service]
Type=simple
User=csmc
Group=csmc
WorkingDirectory=/srv/csmc/server
UnsetEnvironment=JAVA_TOOL_OPTIONS _JAVA_OPTIONS JDK_JAVA_OPTIONS
ExecStart=/usr/bin/java -Xms1G -Xmx4G -Dcsmc.cache=/srv/csmc/cache -jar fabric-server-launch.jar nogui
Restart=on-failure
RestartSec=10
KillSignal=SIGTERM
SuccessExitStatus=143
TimeoutStopSec=180
UMask=0027
NoNewPrivileges=true
PrivateTmp=true
ProtectHome=true
ProtectSystem=strict
ReadWritePaths=/srv/csmc/server /srv/csmc/cache

[Install]
WantedBy=multi-user.target
```

The service does not inherit development flags such as `csmc.devSkin`, which can overwrite a joining player's selected inventory slot. Keep development screenshot/preload hooks out of production too. Minecraft's JVM shutdown hook handles SIGTERM; `systemctl stop` must finish and logs must show saved dimensions before backups or upgrades. Do not use `kill -9` for normal shutdown.

```sh
sudo systemd-analyze verify /etc/systemd/system/csmc.service
sudo systemctl daemon-reload
sudo systemctl enable --now csmc.service
sudo systemctl status csmc.service --no-pager
sudo journalctl -u csmc.service -n 100 --no-pager
ss -ltn 'sport = :25565'
```

The previously failed wait-online unit should be understood before relying on unattended boot/network ordering. Do not change its configuration blindly to make the system-wide status green.

## 6. Tailnet first, then public port forwarding

### Private connectivity

Keep the router closed initially. Inspect Tailscale access grants/ACLs and host firewall routing for the intended players and TCP 25565. Tailscale-managed netfilter chains can affect packet handling before UFW rules; do not assume a UFW rule alone implements tailnet identity restrictions.

If needed after reviewing existing rules, add a narrow host rule without resetting the firewall:

```sh
sudo ufw allow in on tailscale0 to any port 25565 proto tcp
```

From a permitted tailnet client: `nc -vz <server>.<tailnet>.ts.net 25565`, then join using the real Fabric game client. A TCP check alone is not proof of mod/protocol compatibility.

### Router forwarding checklist

1. In the authorized router's admin UI, reserve the server's LAN address. Current candidate is `<server-lan-ip>`; verify its NIC/address after Ethernet changes. Use its actual MAC from `ip link`, not a guessed address.
2. Compare the router's **WAN IPv4** with the server's apparent public IPv4 (`curl -4 https://api.ipify.org`). The public response alone does not rule out CGNAT/double NAT. A WAN address in private ranges or `100.64.0.0/10`, or a mismatch, requires understanding the upstream router/ISP before forwarding. Keep using Tailscale if direct inbound IPv4 is unavailable.
3. Add just one forward: **TCP external 25565 → reserved server LAN IP, TCP internal 25565**. Never target the Tailscale IP, select UDP unnecessarily, enable DMZ, or expose SSH/RCON.
4. Review the host firewall. For the observed Wi-Fi interface and verified reserved IPv4, the rule is `sudo ufw allow in on <lan-interface> to <server-lan-ip> port 25565 proto tcp`. Recheck both interface and IPv4 after Ethernet/DHCP changes. The explicit IPv4 destination avoids accidentally allowing public IPv6 traffic. Do not enable/reset UFW or change its default policies remotely as a side effect of this guide.
5. Publish a public DNS A record/DDNS name if desired, pointing to the public WAN IP. The tailnet MagicDNS name is not a public endpoint for non-Tailscale players. Review existing IPv6 exposure separately: omitting an AAAA record does not block access by literal IPv6. Enable IPv6 gameplay/firewall rules and publish AAAA only as a deliberate separate decision; IPv6 does not use the IPv4 port-forward rule.
6. Test from **outside the home LAN with Tailscale disabled**, e.g. a separate connection/mobile hotspot: `nc -vz YOUR_PUBLIC_IP_OR_DNS 25565`, then join with the same Fabric/CSMC build and an allowlisted authenticated account. A home-network-only test may be affected by NAT loopback and does not establish public reachability.
7. Verify a non-allowlisted account is refused, game actions synchronize between two real clients, and SSH/monitoring remain private. Router UI steps cannot be made model-specific until its model and admin access are known.

## 7. Client setup and readiness checks

Every player needs Minecraft Java 1.21.1, Java 21, Fabric Loader 0.19.5, Fabric API 0.116.17+1.21.1 and the **exact frozen CSMC JAR**. Use a real authenticated launcher profile, not `./gradlew runLanClient1/2`. Reuse an existing modded profile only after checking its mod versions and config.

Each player follows [install.md](install.md) for a lawful local export. The assets are local to each client; no port forwarding fixes a missing local model, glove texture, skin input, or stale catalog. Fully restart clients after changing the export/catalog unless the relevant reload path is verified for that release.

Before inviting players, check:

- Server reports `Done`, expected weapon/surface counts, non-empty skin catalog, and no fatal mixin/class or payload registration errors.
- Both real clients join with matching mod/JAR hashes; test gun/knife/grenade behavior, armor, skins/cases, scopes and HUD. On reload, magazine gain must equal reserve loss.
- Allowlist and online authentication work; port checks alone are insufficient.
- No RCON/query listener was enabled; no public SSH/monitoring forward exists.
- Logs and actual free RAM/tick times are healthy under load. Increase heap/player count only after measuring.

## 8. Development world and inventory migration

**A world copy does not automatically preserve inventories for real accounts.** The current LAN uses `online-mode=false` and fake `CsmcPlayer1/2` profiles. Real account UUIDs differ. Do not turn off production authentication to avoid this migration.

For migration, first stop the development server cleanly and back up the entire `mod/run-server/csmc-test/` directory. Copy the whole world (dimensions, entities, playerdata, POIs, data, datapacks, advancements and stats), not just `region/`. Set `level-name=csmc-test` if retaining that directory name. Never open it once without CSMC installed.

Development UUIDs observed in `usercache.json`:

| Profile | Offline UUID |
|---|---|
| CsmcPlayer1 | `42090f70-2a4d-3a62-8045-f9ef7fffb449` |
| CsmcPlayer2 | `ea99c76d-0bba-3991-93dd-4c09ffdd47b0` |

Before moving player data, explicitly map each development player to the owner's intended authenticated account UUID. On a disposable copy, use an NBT-aware migration to handle player files/embedded identity, stats/advancements, ownership and any arena/team/score references. Do not blindly overwrite already-created real player data. This guide intentionally supplies no invented account mapping or unsafe filename-only migration command.

Test that armor/enchantments, glove/skin components, magazines, attachment items, chests, positions and arena state survived. Select an idle arena snapshot and review its required command blocks/datapacks. Keep the original world unchanged until acceptance.

## 9. Backups, upgrades and rollback

Before any upgrade, obtain a **clean stop**, then snapshot the complete installation/cache (including libraries, mod JARs, world, configs and metadata). These commands are for deployment time, not the current development server:

```sh
(
set -eu
sudo systemctl stop csmc.service
if sudo systemctl is-active --quiet csmc.service; then
  echo "Server is still running; refusing backup" >&2
  exit 1
fi
sudo journalctl -u csmc.service -n 50 --no-pager
# Continue only after saved-dimensions messages and confirmed process exit.
STAMP=$(date -u +%Y%m%dT%H%M%SZ)
sudo tar -C /srv/csmc -czf "/srv/csmc/backups/$STAMP.tgz" server cache
sudo chmod 0600 "/srv/csmc/backups/$STAMP.tgz"
sudo sha256sum "/srv/csmc/backups/$STAMP.tgz"
sudo tar -tzf "/srv/csmc/backups/$STAMP.tgz" >/dev/null
)
```

For a **backup only**, restart with `sudo systemctl start csmc.service` after successful archive verification. For an **upgrade**, leave it stopped until replacement is complete.

Copy backups off this host to a private destination and periodically rehearse restoration. Same-disk archives alone do not protect against disk loss. Watch root free space and set a retention policy before automating backups; no arbitrary retention/deletion job was installed.

For an upgrade, freeze/review the new artifacts and matching private metadata, stop, back up, and replace the old mod JAR/cache while the service remains stopped (never leave two CSMC versions installed). Verify replacement checksums, then explicitly run `sudo systemctl start csmc.service`, upgrade matching clients and run acceptance checks. Do not live-copy a changing world or introduce a new cache halfway through a running release.

For rollback, stop; preserve the failed installation separately; restore the **entire matched pre-upgrade server/cache snapshot into an empty destination**, restore ownership, and start. Clients must use its matching JAR/export snapshot too. Newer world/components may not be downgrade-compatible, so simply swapping back a JAR is not a safe rollback. Use the snapshot's recorded Java/Fabric versions; do not alter the OS JRE blindly.

To withdraw public access, remove the router's game forward and the specific public-interface UFW allow rule you added. Keep administration over Tailscale; do not disable its service or reset the shared firewall.

## Status, limits and references

Written from a read-only inspection of the reference host: tailnet discovery; strict-host-key-checked SSH as `<user>`; Linux OS/resources/network/interfaces/listeners; Java absence and APT candidate; the failed wait-online unit; repository dependency pins, cache consumers, development profiles/config. No software was installed, no firewall/router/service was changed, and no production deployment/gameplay/public-internet test was run. Installation, systemd and transfer commands are prepared but unexecuted. Router permissions/model/WAN topology, final release artifact hashes and real-account migration remain deployment-time inputs.

- [Fabric server launcher](https://fabricmc.net/use/server/), [headless install guide](https://wiki.fabricmc.net/player:tutorials:install_server), [installer CLI](https://wiki.fabricmc.net/install#cli_installation)
- [Fabric metadata API](https://github.com/FabricMC/fabric-meta), [installer versions](https://meta.fabricmc.net/v2/versions/installer), [Loader pin](https://meta.fabricmc.net/v2/versions/loader/1.21.1/0.19.5)
- [Fabric Java 21/Linux guidance](https://docs.fabricmc.net/1.21.1/players/installing-java/linux), [Loom packaging](https://docs.fabricmc.net/develop/loom/)
- [Ubuntu firewall guidance](https://documentation.ubuntu.com/server/how-to/security/firewalls/)
- [Tailscale private Minecraft connectivity](https://tailscale.com/docs/solutions/set-up-minecraft) explains tailnet connectivity; its Bedrock installation commands are **not** this Java server's installation procedure.
- [systemd service reference](https://www.freedesktop.org/software/systemd/man/latest/systemd.service.html), [Minecraft EULA](https://www.minecraft.net/eula)

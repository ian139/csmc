# Third-party licensing boundaries

The root MIT license grants rights only to original contributions whose rights the project contributors hold. It does not relicense third-party code, derived material, game assets, trademarks, or dependencies.

## Valve-derived movement

`mod/src/main/java/dev/csmc/movement/physics/CsGameMovement.java` identifies itself as a port of Valve's Source SDK 2013 movement implementation. Related provenance is recorded in `docs/dev/movement-reference.md` and source comments. These Valve-derived portions are excluded from the project's MIT grant. Their applicable upstream licensing and redistribution requirements have not been cleared by this project; this notice is not permission to redistribute them. Keep this repository private pending that review.

Upstream reference: https://github.com/ValveSoftware/source-sdk-2013/tree/0d8dceea4310fde5706b3ce1c70609d72a38efdf

## Workshop map geometry

The `am_grass2` geometry transcribed in `tools/csmc_workshop_map.py` retains its separate GPL-2.0 terms, attribution, and provenance in `tools/licenses/am_grass2-GPL-2.0.txt`. It is not relicensed as MIT.

## Game assets and screenshots

Counter-Strike models, textures, sounds, and game data exported by users are not covered by MIT and must remain in their own local cache, outside this repository. Screenshots and recipe/catalogue images depicting third-party game content do not confer rights to the underlying game content. Minecraft and Counter-Strike names and trademarks remain their respective owners' property.

NOT AN OFFICIAL MINECRAFT PRODUCT. NOT APPROVED BY OR ASSOCIATED WITH MOJANG OR MICROSOFT.

## Dependencies

External dependencies retain their respective licenses. The Gradle wrapper includes its own Apache-2.0 notice. An MIT license on this repository does not supersede dependency notices or authorize redistribution of externally installed tools or games.

# Dead Walking (Fabric, Minecraft 26.3)

When a player dies, a body stays behind for **50 ticks** (2.5 s) in the pose they died in:
mid-stride if they were walking, sneaking, swimming, facing/looking the same way, with their
skin and armor/held items. It plays vanilla's fall-over animation, then vanishes with the usual poof.
The body stays even if the player hits Respawn right away.

## Build

Needs Java 25 and Gradle 9.6.0.

```
gradle wrapper --gradle-version 9.6.0     # once, creates ./gradlew
./gradlew build
```

The jar is `build/libs/deadwalking-1.0.0.jar`.

## Install

Fabric Loader 0.19.5+ and Fabric API 0.161.0+26.3 for Minecraft 26.3.

- Server: put the jar in `mods`. Players without the mod still see the body, but its limbs relax
  after a few ticks instead of staying frozen.
- Clients: install the jar too to get the frozen mid-stride legs and arms.

## How it works

- `ServerLivingEntityEvents.ALLOW_DEATH` copies the player (position, facing, pose, skin, gear)
  onto a vanilla Mannequin entity with 0 health, which every client already renders as a fallen body.
  The real dying body is hidden so there are no duplicates.
- A server-side tracker mirrors vanilla's walk animation maths, so the exact limb position at death
  is known. It is sent to nearby clients in a small custom packet.
- A client mixin on `LivingEntityRenderer.extractRenderState` pins the limbs to those values.
- Every tick the body's `deathTime` is reset so vanilla doesn't remove it after 20 ticks; the mod
  removes it itself at tick 50 (change `CORPSE_TICKS` in `DeadWalking.java`).

## If it doesn't compile

This was written against 26.3's unobfuscated (Mojang) names without compiling it. The most likely
spots to need a one-word fix:

- `Identifier.fromNamespaceAndPath` (FreezePayload) and `PayloadTypeRegistry.playS2C()` (DeadWalking)
- `EntityType.MANNEQUIN.create(level, EntitySpawnReason.TRIGGERED)` and `Mannequin`'s package
  (`net.minecraft.world.entity.decoration`) in Corpses
- The `extractRenderState(...)` descriptor and `walkAnimationPos` / `walkAnimationSpeed` fields in
  `LivingEntityRendererMixin`

Skin and the hidden "Mannequin" label are set by reflection; if those methods were renamed the body
shows a default skin and the log prints a warning.

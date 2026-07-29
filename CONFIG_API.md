# RPG-Hud Config API

`RPGHudConfigApi` is a client-side, in-process API that lets another Fabric mod inspect and update RPG-Hud configuration without mutating RPG-Hud's internal `Setting` objects.

## Availability

The API class is:

```java
net.spellcraftgaming.rpghud.api.RPGHudConfigApi
```

Current breaking-change version:

```java
RPGHudConfigApi.API_VERSION // 1
```

The API is available after RPG-Hud has completed its client initializer. Consumers should call it from the Minecraft client thread.

## Consumer dependency

Until RPG-Hud is published to a Maven repository, place the RPG-Hud jar in the consumer mod's `libs` directory:

```groovy
dependencies {
    modCompileOnly files("libs/rpg-hud.jar")
}
```

Add a dependency in the consumer's `fabric.mod.json` when RPG-Hud is required:

```json
{
  "depends": {
    "rpg-hud": "*"
  }
}
```

Use `suggests` instead of `depends` when the integration is optional, and check `RPGHudConfigApi.isReady()` before using it.

## Read configuration

```java
if (RPGHudConfigApi.isReady()) {
    Map<String, RPGHudConfigApi.ConfigEntry> config = RPGHudConfigApi.snapshot();

    RPGHudConfigApi.ConfigEntry clock = config.get("enable_clock");
    boolean enabled = (Boolean) clock.value();
}
```

A `ConfigEntry` contains:

- ID and value type
- current and default value
- HUD element group (`general`, `clock`, `health`, and so on)
- allowed values for enum/HUD settings
- numeric metadata when RPG-Hud defines it

Positions are exposed as `RPGHudConfigApi.Position`, not as RPG-Hud's internal `"x_y"` string.

Typed read:

```java
boolean clockEnabled = RPGHudConfigApi
        .get("enable_clock", Boolean.class)
        .orElse(false);
```

## Update one value

```java
RPGHudConfigApi.UpdateResult result =
        RPGHudConfigApi.set("enable_clock", false, true);

if (!result.success()) {
    System.err.println(result.error());
}
```

The final argument controls persistence:

- `true`: update memory and immediately save `config/RPG-HUD.cfg`
- `false`: update memory only; call `RPGHudConfigApi.save()` later

Position example:

```java
RPGHudConfigApi.set(
        "clock_position",
        new RPGHudConfigApi.Position(12, -8),
        true
);
```

Color values accept an integer or `#RRGGBB` / `#AARRGGBB`:

```java
RPGHudConfigApi.set("color_health", "#E53935", true);
```

## Atomic batch update

All values are validated before any setting is changed. The config is saved only once.

```java
Map<String, Object> changes = new LinkedHashMap<>();
changes.put("enable_clock", true);
changes.put("enable_system_time", false);
changes.put("clock_position", new RPGHudConfigApi.Position(8, 8));

RPGHudConfigApi.BatchUpdateResult result =
        RPGHudConfigApi.setAll(changes, true);

if (!result.success()) {
    result.results().forEach((id, update) ->
            System.err.println(id + ": " + update.error()));
}
```

## Save, reload, and reset

```java
RPGHudConfigApi.save();
RPGHudConfigApi.reload();
RPGHudConfigApi.reset("enable_clock", true);
RPGHudConfigApi.resetAll(true);
```

## Change listener

Listeners receive successful changes made through this API and changes detected by `reload()`.

```java
RPGHudConfigApi.ListenerHandle handle = RPGHudConfigApi.addListener(change -> {
    System.out.printf(
            "RPG-Hud config changed: %s: %s -> %s%n",
            change.id(),
            change.previousValue(),
            change.currentValue()
    );
});

// Remove the listener when the integration shuts down.
handle.close();
```

The listener is not a cross-process or network API. Both mods must be loaded in the same Minecraft client.

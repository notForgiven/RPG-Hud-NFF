package net.spellcraftgaming.rpghud.api;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.spellcraftgaming.rpghud.main.ModRPGHud;
import net.spellcraftgaming.rpghud.settings.Setting;
import net.spellcraftgaming.rpghud.settings.SettingBoolean;
import net.spellcraftgaming.rpghud.settings.SettingColor;
import net.spellcraftgaming.rpghud.settings.SettingDouble;
import net.spellcraftgaming.rpghud.settings.SettingFloat;
import net.spellcraftgaming.rpghud.settings.SettingHudType;
import net.spellcraftgaming.rpghud.settings.SettingInteger;
import net.spellcraftgaming.rpghud.settings.SettingPosition;
import net.spellcraftgaming.rpghud.settings.SettingString;
import net.spellcraftgaming.rpghud.settings.Settings;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Public client-side API for reading and changing RPG-Hud configuration.
 *
 * <p>Calls are synchronous. Consumers should call this API from the Minecraft
 * client thread. Values returned by this class are detached snapshots; external
 * mods never receive RPG-Hud's mutable {@link Setting} instances.</p>
 */
@Environment(EnvType.CLIENT)
public final class RPGHudConfigApi {

    /** Increment this only for breaking API changes. */
    public static final int API_VERSION = 1;

    private static final Pattern POSITION_PATTERN = Pattern.compile("^(-?\\d+)_(-?\\d+)$");
    private static final CopyOnWriteArrayList<ConfigChangeListener> LISTENERS = new CopyOnWriteArrayList<>();

    private RPGHudConfigApi() {
    }

    /**
     * Returns whether RPG-Hud has completed client initialization and its config is available.
     */
    public static boolean isReady() {
        return ModRPGHud.instance != null && ModRPGHud.instance.settings != null;
    }

    /**
     * Returns the path of RPG-Hud's config file.
     */
    public static Path getConfigPath() {
        return requireSettings().getConfigFile().toPath();
    }

    /**
     * Returns all known setting IDs in stable config-file order.
     */
    public static Set<String> getSettingIds() {
        return Collections.unmodifiableSet(new LinkedHashSet<>(requireSettings().getSettingIds()));
    }

    /**
     * Returns a detached descriptor for one setting.
     */
    public static Optional<ConfigEntry> find(String id) {
        Objects.requireNonNull(id, "id");
        if(!isReady()) {
            return Optional.empty();
        }

        Setting setting = ModRPGHud.instance.settings.getSetting(id);
        return setting == null ? Optional.empty() : Optional.of(describe(setting));
    }

    /**
     * Returns a detached snapshot of every setting.
     */
    public static Map<String, ConfigEntry> snapshot() {
        Settings settings = requireSettings();
        Map<String, ConfigEntry> snapshot = new LinkedHashMap<>();
        for(String id : settings.getSettingIds()) {
            Setting setting = settings.getSetting(id);
            if(setting != null) {
                snapshot.put(id, describe(setting));
            }
        }
        return Collections.unmodifiableMap(snapshot);
    }

    /**
     * Reads and type-checks a setting value.
     */
    public static <T> Optional<T> get(String id, Class<T> valueClass) {
        Objects.requireNonNull(valueClass, "valueClass");
        return find(id)
                .map(ConfigEntry::value)
                .filter(valueClass::isInstance)
                .map(valueClass::cast);
    }

    /**
     * Updates one setting. When {@code persist} is true, the config file is saved immediately.
     */
    public static UpdateResult set(String id, Object value, boolean persist) {
        Objects.requireNonNull(id, "id");

        if(!isReady()) {
            return UpdateResult.failure(UpdateStatus.NOT_READY, id, null,
                    "RPG-Hud config is not initialized yet");
        }

        Settings settings = ModRPGHud.instance.settings;
        Setting setting = settings.getSetting(id);
        if(setting == null) {
            return UpdateResult.failure(UpdateStatus.UNKNOWN_SETTING, id, null,
                    "Unknown RPG-Hud setting: " + id);
        }

        final Object normalized;
        try {
            normalized = normalizeInput(setting, value);
        } catch(IllegalArgumentException exception) {
            return UpdateResult.failure(UpdateStatus.INVALID_VALUE, id, exportValue(setting, setting.getValue()),
                    exception.getMessage());
        }

        Object previousValue = exportValue(setting, setting.getValue());
        settings.setSetting(id, normalized);
        Object currentValue = exportValue(setting, setting.getValue());

        if(Objects.equals(previousValue, currentValue)) {
            if(persist) {
                settings.saveSettings();
            }
            return new UpdateResult(UpdateStatus.UNCHANGED, id, previousValue, currentValue, null);
        }

        if(persist) {
            settings.saveSettings();
        }

        fireChange(new ConfigChange(id, previousValue, currentValue, persist));
        return new UpdateResult(UpdateStatus.UPDATED, id, previousValue, currentValue, null);
    }

    /**
     * Validates every value first, then applies the whole batch and optionally saves once.
     * If any value is invalid, no setting is changed.
     */
    public static BatchUpdateResult setAll(Map<String, ?> updates, boolean persist) {
        Objects.requireNonNull(updates, "updates");

        if(!isReady()) {
            return new BatchUpdateResult(false, Map.of("*",
                    UpdateResult.failure(UpdateStatus.NOT_READY, "*", null,
                            "RPG-Hud config is not initialized yet")));
        }

        Settings settings = ModRPGHud.instance.settings;
        Map<String, Object> normalizedValues = new LinkedHashMap<>();
        Map<String, UpdateResult> validationFailures = new LinkedHashMap<>();

        for(Map.Entry<String, ?> update : updates.entrySet()) {
            String id = update.getKey();
            if(id == null) {
                validationFailures.put("<null>", UpdateResult.failure(
                        UpdateStatus.UNKNOWN_SETTING, "<null>", null, "Setting ID cannot be null"));
                continue;
            }

            Setting setting = settings.getSetting(id);
            if(setting == null) {
                validationFailures.put(id, UpdateResult.failure(
                        UpdateStatus.UNKNOWN_SETTING, id, null, "Unknown RPG-Hud setting: " + id));
                continue;
            }

            try {
                normalizedValues.put(id, normalizeInput(setting, update.getValue()));
            } catch(IllegalArgumentException exception) {
                validationFailures.put(id, UpdateResult.failure(
                        UpdateStatus.INVALID_VALUE, id, exportValue(setting, setting.getValue()), exception.getMessage()));
            }
        }

        if(!validationFailures.isEmpty()) {
            return new BatchUpdateResult(false, Collections.unmodifiableMap(validationFailures));
        }

        Map<String, UpdateResult> results = new LinkedHashMap<>();
        List<ConfigChange> changes = new ArrayList<>();
        for(Map.Entry<String, Object> update : normalizedValues.entrySet()) {
            String id = update.getKey();
            Setting setting = settings.getSetting(id);
            Object previousValue = exportValue(setting, setting.getValue());
            settings.setSetting(id, update.getValue());
            Object currentValue = exportValue(setting, setting.getValue());

            UpdateStatus status = Objects.equals(previousValue, currentValue)
                    ? UpdateStatus.UNCHANGED
                    : UpdateStatus.UPDATED;
            results.put(id, new UpdateResult(status, id, previousValue, currentValue, null));
            if(status == UpdateStatus.UPDATED) {
                changes.add(new ConfigChange(id, previousValue, currentValue, persist));
            }
        }

        if(persist) {
            settings.saveSettings();
        }
        changes.forEach(RPGHudConfigApi::fireChange);

        return new BatchUpdateResult(true, Collections.unmodifiableMap(results));
    }

    /**
     * Resets one setting to its default value.
     */
    public static UpdateResult reset(String id, boolean persist) {
        Objects.requireNonNull(id, "id");
        if(!isReady()) {
            return UpdateResult.failure(UpdateStatus.NOT_READY, id, null,
                    "RPG-Hud config is not initialized yet");
        }

        Setting setting = ModRPGHud.instance.settings.getSetting(id);
        if(setting == null) {
            return UpdateResult.failure(UpdateStatus.UNKNOWN_SETTING, id, null,
                    "Unknown RPG-Hud setting: " + id);
        }
        return set(id, setting.getDefaultValue(), persist);
    }

    /**
     * Resets every setting to its default value.
     */
    public static BatchUpdateResult resetAll(boolean persist) {
        if(!isReady()) {
            return new BatchUpdateResult(false, Map.of("*",
                    UpdateResult.failure(UpdateStatus.NOT_READY, "*", null,
                            "RPG-Hud config is not initialized yet")));
        }

        Settings settings = ModRPGHud.instance.settings;
        Map<String, Object> defaults = new LinkedHashMap<>();
        for(String id : settings.getSettingIds()) {
            Setting setting = settings.getSetting(id);
            if(setting != null) {
                defaults.put(id, setting.getDefaultValue());
            }
        }
        return setAll(defaults, persist);
    }

    /** Saves current in-memory values to RPG-Hud's config file. */
    public static void save() {
        requireSettings().saveSettings();
    }

    /**
     * Reloads values from disk and emits change events for values that changed.
     */
    public static void reload() {
        Settings settings = requireSettings();
        Map<String, ConfigEntry> before = snapshot();
        settings.load();
        Map<String, ConfigEntry> after = snapshot();

        for(Map.Entry<String, ConfigEntry> entry : after.entrySet()) {
            ConfigEntry previous = before.get(entry.getKey());
            if(previous != null && !Objects.equals(previous.value(), entry.getValue().value())) {
                fireChange(new ConfigChange(entry.getKey(), previous.value(), entry.getValue().value(), false));
            }
        }
    }

    /**
     * Registers a listener for changes made through this API or by {@link #reload()}.
     */
    public static ListenerHandle addListener(ConfigChangeListener listener) {
        Objects.requireNonNull(listener, "listener");
        LISTENERS.add(listener);
        return () -> LISTENERS.remove(listener);
    }

    /** Removes a previously registered listener. */
    public static void removeListener(ConfigChangeListener listener) {
        LISTENERS.remove(listener);
    }

    private static Settings requireSettings() {
        if(!isReady()) {
            throw new IllegalStateException("RPG-Hud config is not initialized yet");
        }
        return ModRPGHud.instance.settings;
    }

    private static ConfigEntry describe(Setting setting) {
        ValueType type = valueType(setting);
        List<String> allowedValues = switch(setting) {
            case SettingString stringSetting -> List.of(stringSetting.possibleValues.clone());
            case SettingHudType ignored -> ModRPGHud.instance == null
                    ? List.of()
                    : List.copyOf(ModRPGHud.instance.huds.keySet());
            default -> List.of();
        };

        Double min = null;
        Double max = null;
        Double step = null;
        if(setting instanceof SettingInteger integerSetting) {
            min = (double) integerSetting.minValue;
            max = (double) integerSetting.maxValue;
            step = 1.0D;
        } else if(setting instanceof SettingFloat floatSetting) {
            min = (double) floatSetting.minValue;
            max = (double) floatSetting.maxValue;
            step = (double) floatSetting.step;
        } else if(setting instanceof SettingDouble doubleSetting) {
            min = doubleSetting.minValue;
            max = doubleSetting.maxValue;
            step = doubleSetting.step;
        }

        String group = setting.associatedType == null
                ? "general"
                : setting.associatedType.name().toLowerCase(Locale.ROOT);

        return new ConfigEntry(
                setting.ID,
                type,
                exportValue(setting, setting.getValue()),
                exportValue(setting, setting.getDefaultValue()),
                group,
                allowedValues,
                min,
                max,
                step
        );
    }

    private static ValueType valueType(Setting setting) {
        return switch(setting) {
            case SettingBoolean ignored -> ValueType.BOOLEAN;
            case SettingColor ignored -> ValueType.COLOR;
            case SettingInteger ignored -> ValueType.INTEGER;
            case SettingFloat ignored -> ValueType.FLOAT;
            case SettingDouble ignored -> ValueType.DOUBLE;
            case SettingPosition ignored -> ValueType.POSITION;
            case SettingHudType ignored -> ValueType.HUD_TYPE;
            case SettingString ignored -> ValueType.ENUM_STRING;
            default -> ValueType.UNKNOWN;
        };
    }

    private static Object normalizeInput(Setting setting, Object value) {
        if(value == null) {
            throw new IllegalArgumentException("Value for " + setting.ID + " cannot be null");
        }

        return switch(setting) {
            case SettingBoolean ignored -> {
                if(value instanceof Boolean booleanValue) {
                    yield booleanValue;
                }
                throw expected(setting, "Boolean", value);
            }
            case SettingColor ignored -> normalizeColor(setting, value);
            case SettingInteger ignored -> normalizeInteger(setting, value);
            case SettingFloat ignored -> normalizeFloat(setting, value);
            case SettingDouble ignored -> normalizeDouble(setting, value);
            case SettingPosition ignored -> normalizePosition(setting, value);
            case SettingHudType ignored -> normalizeHudType(setting, value);
            case SettingString stringSetting -> normalizeEnumString(stringSetting, value);
            default -> throw new IllegalArgumentException(
                    "Unsupported RPG-Hud setting type for " + setting.ID + ": " + setting.getClass().getName());
        };
    }

    private static Integer normalizeColor(Setting setting, Object value) {
        if(value instanceof Number number) {
            return checkedInt(setting, number);
        }
        if(value instanceof String text) {
            String hex = text.trim();
            if(hex.startsWith("#")) {
                hex = hex.substring(1);
            }
            if(!hex.matches("[0-9a-fA-F]{6}|[0-9a-fA-F]{8}")) {
                throw new IllegalArgumentException(
                        "Color " + setting.ID + " must be an integer or #RRGGBB/#AARRGGBB string");
            }
            return (int) Long.parseUnsignedLong(hex, 16);
        }
        throw expected(setting, "integer or hex color string", value);
    }

    private static Integer normalizeInteger(Setting setting, Object value) {
        if(value instanceof Number number) {
            return checkedInt(setting, number);
        }
        throw expected(setting, "integer", value);
    }

    private static Integer checkedInt(Setting setting, Number number) {
        double asDouble = number.doubleValue();
        if(!Double.isFinite(asDouble) || asDouble != Math.rint(asDouble)
                || asDouble < Integer.MIN_VALUE || asDouble > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Value for " + setting.ID + " must be a 32-bit integer");
        }
        return (int) asDouble;
    }

    private static Float normalizeFloat(Setting setting, Object value) {
        if(value instanceof Number number) {
            float result = number.floatValue();
            if(!Float.isFinite(result)) {
                throw new IllegalArgumentException("Value for " + setting.ID + " must be finite");
            }
            return result;
        }
        throw expected(setting, "number", value);
    }

    private static Double normalizeDouble(Setting setting, Object value) {
        if(value instanceof Number number) {
            double result = number.doubleValue();
            if(!Double.isFinite(result)) {
                throw new IllegalArgumentException("Value for " + setting.ID + " must be finite");
            }
            return result;
        }
        throw expected(setting, "number", value);
    }

    private static String normalizePosition(Setting setting, Object value) {
        if(value instanceof Position position) {
            return position.toConfigValue();
        }
        if(value instanceof int[] coordinates && coordinates.length == 2) {
            return coordinates[0] + "_" + coordinates[1];
        }
        if(value instanceof String text && POSITION_PATTERN.matcher(text.trim()).matches()) {
            return text.trim();
        }
        throw expected(setting, "Position, int[2], or x_y string", value);
    }

    private static String normalizeHudType(Setting setting, Object value) {
        if(!(value instanceof String hudId)) {
            throw expected(setting, "HUD ID string", value);
        }
        if(ModRPGHud.instance == null || !ModRPGHud.instance.huds.containsKey(hudId)) {
            throw new IllegalArgumentException("Unknown HUD type: " + hudId);
        }
        return hudId;
    }

    private static String normalizeEnumString(SettingString setting, Object value) {
        if(!(value instanceof String text)) {
            throw expected(setting, "one of " + List.of(setting.possibleValues), value);
        }
        for(String possibleValue : setting.possibleValues) {
            if(possibleValue.equals(text)) {
                return text;
            }
        }
        throw new IllegalArgumentException(
                "Value for " + setting.ID + " must be one of " + List.of(setting.possibleValues));
    }

    private static IllegalArgumentException expected(Setting setting, String expected, Object actual) {
        return new IllegalArgumentException(
                "Value for " + setting.ID + " must be " + expected + ", got " + actual.getClass().getSimpleName());
    }

    private static Object exportValue(Setting setting, Object value) {
        if(setting instanceof SettingPosition && value instanceof String text) {
            Matcher matcher = POSITION_PATTERN.matcher(text);
            if(matcher.matches()) {
                return new Position(Integer.parseInt(matcher.group(1)), Integer.parseInt(matcher.group(2)));
            }
        }
        return value;
    }

    private static void fireChange(ConfigChange change) {
        for(ConfigChangeListener listener : LISTENERS) {
            try {
                listener.onConfigChanged(change);
            } catch(RuntimeException exception) {
                System.err.println("RPG-Hud config listener failed: " + exception.getMessage());
                exception.printStackTrace();
            }
        }
    }

    public enum ValueType {
        BOOLEAN,
        INTEGER,
        FLOAT,
        DOUBLE,
        COLOR,
        POSITION,
        ENUM_STRING,
        HUD_TYPE,
        UNKNOWN
    }

    public enum UpdateStatus {
        UPDATED,
        UNCHANGED,
        NOT_READY,
        UNKNOWN_SETTING,
        INVALID_VALUE
    }

    /** Immutable public representation of an x/y HUD offset. */
    public record Position(int x, int y) {
        public String toConfigValue() {
            return x + "_" + y;
        }
    }

    /** Immutable metadata and value snapshot for one setting. */
    public record ConfigEntry(
            String id,
            ValueType type,
            Object value,
            Object defaultValue,
            String group,
            List<String> allowedValues,
            Double min,
            Double max,
            Double step
    ) {
        public ConfigEntry {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(type, "type");
            Objects.requireNonNull(group, "group");
            allowedValues = List.copyOf(allowedValues);
        }
    }

    /** Result of one config update. */
    public record UpdateResult(
            UpdateStatus status,
            String id,
            Object previousValue,
            Object currentValue,
            String error
    ) {
        public boolean success() {
            return status == UpdateStatus.UPDATED || status == UpdateStatus.UNCHANGED;
        }

        private static UpdateResult failure(UpdateStatus status, String id, Object currentValue, String error) {
            return new UpdateResult(status, id, currentValue, currentValue, error);
        }
    }

    /** Result of an atomic validation + batch update. */
    public record BatchUpdateResult(boolean success, Map<String, UpdateResult> results) {
        public BatchUpdateResult {
            results = Collections.unmodifiableMap(new LinkedHashMap<>(results));
        }
    }

    /** Change notification emitted after an in-memory update has succeeded. */
    public record ConfigChange(String id, Object previousValue, Object currentValue, boolean persisted) {
    }

    @FunctionalInterface
    public interface ConfigChangeListener {
        void onConfigChanged(ConfigChange change);
    }

    @FunctionalInterface
    public interface ListenerHandle extends AutoCloseable {
        @Override
        void close();
    }
}

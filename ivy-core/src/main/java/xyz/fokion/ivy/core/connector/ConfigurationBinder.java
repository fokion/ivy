package xyz.fokion.ivy.core.connector;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import xyz.fokion.ivy.core.connector.ConnectorInfo.PropertyInfo;
import xyz.fokion.ivy.core.util.Cast;
import xyz.fokion.ivy.spi.Configuration;
import xyz.fokion.ivy.spi.ConfigurationProperty;
import xyz.fokion.ivy.spi.ConnectorException;
import xyz.fokion.ivy.spi.util.GoFormat;

/**
 * Binds step keys to a {@link Configuration} bean through its setters annotated with
 * {@link ConfigurationProperty}, matching names case-insensitively like venom's
 * {@code mapstructure} decoding.
 */
public final class ConfigurationBinder {

    private ConfigurationBinder() {
    }

    private record Property(String name, Method setter, ConfigurationProperty annotation) {
    }

    private static List<Property> properties(Class<?> type) {
        List<Property> out = new ArrayList<>();
        for (Method m : type.getMethods()) {
            ConfigurationProperty a = m.getAnnotation(ConfigurationProperty.class);
            if (a == null || m.getParameterCount() != 1) {
                continue;
            }
            String name = a.name();
            if (name.isEmpty()) {
                String n = m.getName().startsWith("set") ? m.getName().substring(3) : m.getName();
                name = n.isEmpty() ? n : Character.toLowerCase(n.charAt(0)) + n.substring(1);
            }
            out.add(new Property(name, m, a));
        }
        out.sort((x, y) -> x.name().compareTo(y.name()));
        return out;
    }

    public static List<PropertyInfo> describe(Class<? extends Configuration> type) {
        List<PropertyInfo> out = new ArrayList<>();
        for (Property p : properties(type)) {
            out.add(new PropertyInfo(p.name(), p.setter().getGenericParameterTypes()[0].getTypeName(),
                    p.annotation().required(), p.annotation().secret(), p.annotation().help()));
        }
        return out;
    }

    /** Creates the configuration and binds the step keys to it, then validates it. */
    public static <C extends Configuration> C bind(Class<C> type, Map<String, Object> step) {
        C config;
        try {
            config = type.getDeclaredConstructor().newInstance();
        } catch (ReflectiveOperationException e) {
            throw new ConnectorException("cannot create configuration " + type.getName() + ": " + e.getMessage(), e);
        }
        Map<String, Object> lower = new LinkedHashMap<>();
        step.forEach((k, v) -> lower.putIfAbsent(k.toLowerCase(Locale.ROOT), v));
        for (Property p : properties(type)) {
            String key = p.name().toLowerCase(Locale.ROOT);
            if (!lower.containsKey(key)) {
                if (p.annotation().required()) {
                    throw new ConnectorException("missing required property \"" + p.name() + "\"");
                }
                continue;
            }
            Object value = lower.get(key);
            if (value == null) {
                continue;
            }
            try {
                p.setter().invoke(config, convert(value, p.setter().getGenericParameterTypes()[0], p.name()));
            } catch (IllegalAccessException e) {
                throw new ConnectorException("cannot set " + p.name() + ": " + e.getMessage(), e);
            } catch (InvocationTargetException e) {
                Throwable cause = e.getCause();
                throw new ConnectorException("cannot set " + p.name() + ": " + cause.getMessage(), cause);
            }
        }
        config.validate();
        return config;
    }

    static Object convert(Object value, Type target, String name) {
        Class<?> raw = rawType(target);
        try {
            if (raw == Object.class) {
                return value;
            }
            if (raw == String.class) {
                return Cast.toStringStrict(value);
            }
            if (raw == int.class || raw == Integer.class) {
                return (int) Cast.toLongStrict(value);
            }
            if (raw == long.class || raw == Long.class) {
                return Cast.toLongStrict(value);
            }
            if (raw == double.class || raw == Double.class) {
                return Cast.toDoubleStrict(value);
            }
            if (raw == boolean.class || raw == Boolean.class) {
                return Cast.toBoolStrict(value);
            }
            if (List.class.isAssignableFrom(raw)) {
                Type element = typeArgument(target, 0);
                List<Object> out = new ArrayList<>();
                if (value instanceof Collection<?> c) {
                    for (Object e : c) {
                        out.add(convert(e, element, name));
                    }
                } else {
                    out.add(convert(value, element, name));
                }
                return out;
            }
            if (Map.class.isAssignableFrom(raw)) {
                if (!(value instanceof Map<?, ?> m)) {
                    throw new ConnectorException("'" + name + "' expected a map, got " + GoFormat.sprint(value));
                }
                Type element = typeArgument(target, 1);
                Map<String, Object> out = new LinkedHashMap<>();
                m.forEach((k, v) -> out.put(String.valueOf(k), v == null ? null : convert(v, element, name)));
                return out;
            }
        } catch (Cast.CastException e) {
            throw new ConnectorException("'" + name + "': " + e.getMessage(), e);
        }
        throw new ConnectorException("'" + name + "': unsupported property type " + target.getTypeName());
    }

    private static Class<?> rawType(Type t) {
        if (t instanceof Class<?> c) {
            return c;
        }
        if (t instanceof ParameterizedType p) {
            return (Class<?>) p.getRawType();
        }
        return Object.class;
    }

    private static Type typeArgument(Type t, int index) {
        if (t instanceof ParameterizedType p && p.getActualTypeArguments().length > index) {
            return p.getActualTypeArguments()[index];
        }
        return Object.class;
    }
}

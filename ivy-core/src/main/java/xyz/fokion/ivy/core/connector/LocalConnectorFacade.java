package xyz.fokion.ivy.core.connector;

import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.List;

import xyz.fokion.ivy.spi.Configuration;
import xyz.fokion.ivy.spi.Connector;
import xyz.fokion.ivy.spi.ConnectorClass;
import xyz.fokion.ivy.spi.ConnectorException;
import xyz.fokion.ivy.spi.DefaultAssertionsProvider;
import xyz.fokion.ivy.spi.StepContext;

/**
 * Runs a connector class loaded in this JVM, from the classpath or from a bundle.
 */
public final class LocalConnectorFacade implements ConnectorFacade {

    private final Class<? extends Connector<?>> connectorClass;
    private final Class<? extends Configuration> configurationClass;
    private final ConnectorInfo info;

    public LocalConnectorFacade(String bundleName, String bundleVersion, Class<? extends Connector<?>> connectorClass) {
        ConnectorClass annotation = connectorClass.getAnnotation(ConnectorClass.class);
        if (annotation == null) {
            throw new ConnectorException(connectorClass.getName() + " is not annotated with @ConnectorClass");
        }
        this.connectorClass = connectorClass;
        this.configurationClass = annotation.configurationClass();
        Connector<?> probe = newInstance();
        List<Object> defaultAssertions = probe instanceof DefaultAssertionsProvider p
                ? new ArrayList<>(p.defaultAssertions()) : null;
        java.util.Map<String, String> resultFields = probe.resultFields();
        try {
            // the probe was never opened, closing it releases anything its constructor allocated
            probe.close();
        } catch (Exception ignored) {
            // describing the connector succeeded, a failing close changes nothing
        }
        String displayName = annotation.displayName().isEmpty() ? annotation.type() : annotation.displayName();
        this.info = new ConnectorInfo(new ConnectorKey(bundleName, bundleVersion, annotation.type()), displayName,
                ConfigurationBinder.describe(configurationClass), defaultAssertions, resultFields);
    }

    @Override
    public ConnectorInfo info() {
        return info;
    }

    /** The class loader of the connector: its bundle's, for classes the bundle offers besides it. */
    public ClassLoader classLoader() {
        return connectorClass.getClassLoader();
    }

    private Connector<?> newInstance() {
        try {
            Constructor<? extends Connector<?>> c = connectorClass.getDeclaredConstructor();
            return c.newInstance();
        } catch (ReflectiveOperationException e) {
            throw new ConnectorException("cannot create connector " + connectorClass.getName() + ": " + e.getMessage(), e);
        }
    }

    @Override
    public Session openSession(StepContext context) throws Exception {
        Connector<?> connector = newInstance();
        ClassLoader loader = connectorClass.getClassLoader();
        try {
            withContextLoader(loader, () -> {
                connector.open(context);
                return null;
            });
        } catch (Exception e) {
            try {
                connector.close();
            } catch (Exception suppressed) {
                e.addSuppressed(suppressed);
            }
            throw e;
        }
        return new Session() {
            @Override
            @SuppressWarnings({"unchecked", "rawtypes"})
            public Object run(StepContext ctx) throws Exception {
                return withContextLoader(loader, () -> {
                    Configuration config = ConfigurationBinder.bind(configurationClass, ctx.step());
                    return ((Connector) connector).run(config, ctx);
                });
            }

            @Override
            public void close() throws Exception {
                withContextLoader(loader, () -> {
                    connector.close();
                    return null;
                });
            }
        };
    }

    private interface Call<T> {
        T call() throws Exception;
    }

    /** Drivers often look classes up through the context class loader. */
    private static <T> T withContextLoader(ClassLoader loader, Call<T> call) throws Exception {
        Thread t = Thread.currentThread();
        ClassLoader previous = t.getContextClassLoader();
        t.setContextClassLoader(loader);
        try {
            return call.call();
        } finally {
            t.setContextClassLoader(previous);
        }
    }
}

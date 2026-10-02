package xyz.fokion.ivy.cli;

import io.quarkus.runtime.annotations.RegisterForReflection;
import xyz.fokion.ivy.core.connectors.ExecConfiguration;
import xyz.fokion.ivy.core.connectors.ExecConnector;
import xyz.fokion.ivy.core.connectors.HttpConfiguration;
import xyz.fokion.ivy.core.connectors.HttpConnector;
import xyz.fokion.ivy.core.connectors.ReadfileConfiguration;
import xyz.fokion.ivy.core.connectors.ReadfileConnector;

/**
 * Classes the connector framework reaches by reflection (constructors, annotated setters),
 * registered for the native image so that ivy-core stays free of framework annotations.
 */
@RegisterForReflection(targets = {
        ExecConnector.class, ExecConfiguration.class,
        HttpConnector.class, HttpConfiguration.class,
        ReadfileConnector.class, ReadfileConfiguration.class,
})
final class NativeRegistrations {

    private NativeRegistrations() {
    }
}

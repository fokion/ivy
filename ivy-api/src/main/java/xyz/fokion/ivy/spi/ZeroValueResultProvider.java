package xyz.fokion.ivy.spi;

/**
 * Supplies an empty result whose shape tells the parser which {@code result.*} variables the
 * connector produces, so references to them are not reported as missing.
 */
public interface ZeroValueResultProvider {

    Object zeroValueResult();
}

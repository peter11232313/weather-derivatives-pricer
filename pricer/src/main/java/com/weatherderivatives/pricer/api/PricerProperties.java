package com.weatherderivatives.pricer.api;

import java.nio.file.Path;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Configuration under {@code pricer.*}.
 *
 * @param dataDir folder holding {@code <city>.parquet}; default {@code data/pricer}
 */
@ConfigurationProperties("pricer")
public record PricerProperties(@DefaultValue("data/pricer") Path dataDir) {}

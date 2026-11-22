package com.weatherderivatives.pricer.api;

import com.weatherderivatives.pricer.data.ParquetTemperatureLoader;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Wires the default {@link TemperatureSource}: Parquet files in {@code pricer.data-dir}. */
@Configuration
public class TemperatureSourceConfig {

  /** Created by Spring. */
  public TemperatureSourceConfig() {}

  /**
   * The default source.
   *
   * @param properties pricer configuration
   * @return a source reading {@code <dataDir>/<city>.parquet} on every call
   */
  @Bean
  public TemperatureSource temperatureSource(PricerProperties properties) {
    return city -> new ParquetTemperatureLoader(properties.dataDir()).load(city);
  }
}

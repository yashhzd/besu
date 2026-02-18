/*
 * Copyright contributors to Hyperledger Besu.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on
 * an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations under the License.
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package org.hyperledger.besu.cli.logging;

import org.hyperledger.besu.cli.options.LoggingFormat;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.core.Layout;
import org.apache.logging.log4j.core.LifeCycle;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.ConsoleAppender;
import org.apache.logging.log4j.core.config.Configuration;
import org.apache.logging.log4j.core.config.Configurator;
import org.apache.logging.log4j.core.config.LoggerConfig;
import org.apache.logging.log4j.core.config.builder.api.AppenderComponentBuilder;
import org.apache.logging.log4j.core.config.builder.api.ComponentBuilder;
import org.apache.logging.log4j.core.config.builder.api.ConfigurationBuilder;
import org.apache.logging.log4j.core.config.builder.api.ConfigurationBuilderFactory;
import org.apache.logging.log4j.core.config.builder.api.LayoutComponentBuilder;
import org.apache.logging.log4j.core.config.builder.api.LoggerComponentBuilder;
import org.apache.logging.log4j.core.config.builder.api.RootLoggerComponentBuilder;
import org.apache.logging.log4j.core.config.builder.impl.BuiltConfiguration;
import org.apache.logging.log4j.core.filter.MarkerFilter;
import org.apache.logging.log4j.core.filter.RegexFilter;
import org.apache.logging.log4j.core.layout.PatternLayout;
import org.apache.logging.log4j.layout.template.json.JsonTemplateLayout;

/** Programmatic Log4j2 configuration for Besu. */
public class LoggingConfigurator {

  private static final String DEFAULT_PATTERN =
      "%style{%d{yyyy-MM-dd HH:mm:ss.SSSZZZ}}{DIM} %style{|}{DIM} %style{%t}{DIM} %style{|}{DIM} "
          + "%highlight{%-5level}{TRACE=normal} %style{|}{DIM} %style{%c{1}}{DIM} %style{|}{DIM} "
          + "%highlight{%msgc%n%throwable}{TRACE=normal}";

  /**
   * Configure logging programmatically based on CLI options.
   *
   * @param logLevel the log level from CLI (e.g., "INFO", "DEBUG")
   * @param loggingFormat the logging format from CLI
   * @param colorEnabled whether ANSI colors should be enabled
   */
  public static void configureLogging(
      final String logLevel, final LoggingFormat loggingFormat, final boolean colorEnabled) {

    // Get current context and stop/remove existing appenders
    final LoggerContext context = LoggerContext.getContext(false);
    final Configuration oldConfig = context.getConfiguration();

    // Stop and remove all existing appenders
    oldConfig.getAppenders().values().forEach(LifeCycle::stop);
    oldConfig.getRootLogger().getAppenders().clear();

    // Build new configuration
    final ConfigurationBuilder<BuiltConfiguration> builder =
        ConfigurationBuilderFactory.newConfigurationBuilder();

    builder.setStatusLevel(Level.ERROR);
    builder.setConfigurationName("BesuProgrammaticConfig");

    // Determine which appender to use based on environment
    final String loggerType = System.getenv("LOGGER");
    final boolean useSplunk = "Splunk".equals(loggerType);

    if (useSplunk) {
      // Add Splunk appender
      addSplunkAppender(builder);
    } else {
      // Add Console appender with selected format
      addConsoleAppender(builder, loggingFormat, colorEnabled);
    }

    // Add specialized logger filters
    addLoggerFilters(builder);

    // Create root logger
    final Level level = parseLevel(logLevel);
    final RootLoggerComponentBuilder rootLogger = builder.newRootLogger(level);
    rootLogger.add(builder.newAppenderRef(useSplunk ? "Splunk" : "Console"));
    builder.add(rootLogger);

    // Build and apply configuration
    final BuiltConfiguration config = builder.build();
    Configurator.reconfigure(config);
  }

  private static void addConsoleAppender(
      final ConfigurationBuilder<BuiltConfiguration> builder,
      final LoggingFormat loggingFormat,
      final boolean colorEnabled) {

    final LayoutComponentBuilder layoutBuilder;

    if (loggingFormat != null && loggingFormat.isJsonFormat()) {
      // JSON layout for structured logging
      layoutBuilder =
          builder
              .newLayout("JsonTemplateLayout")
              .addAttribute("eventTemplateUri", loggingFormat.getEventTemplateUri());
    } else {
      // Pattern layout for plain text logging
      layoutBuilder =
          builder
              .newLayout("PatternLayout")
              .addAttribute("pattern", DEFAULT_PATTERN)
              .addAttribute("disableAnsi", !colorEnabled)
              .addAttribute("noConsoleNoAnsi", !colorEnabled);
    }

    final AppenderComponentBuilder consoleAppender =
        builder
            .newAppender("Console", "Console")
            .addAttribute("target", ConsoleAppender.Target.SYSTEM_OUT)
            .add(layoutBuilder);

    builder.add(consoleAppender);
  }

  private static void addSplunkAppender(final ConfigurationBuilder<BuiltConfiguration> builder) {
    // Splunk configuration from environment variables
    final String splunkUrl = System.getenv("SPLUNK_URL");
    final String splunkToken = System.getenv("SPLUNK_TOKEN");
    final String splunkIndex = System.getenv("SPLUNK_INDEX");
    final String splunkSource = getEnvOrDefault("SPLUNK_SOURCE", "besu");
    final String splunkSourcetype = getEnvOrDefault("SPLUNK_SOURCETYPE", "besu");
    final String host = getHost();
    final String batchSizeBytes = getEnvOrDefault("SPLUNK_BATCH_SIZE_BYTES", "65536");
    final String batchSizeCount = getEnvOrDefault("SPLUNK_BATCH_SIZE_COUNT", "1000");
    final String batchInterval = getEnvOrDefault("SPLUNK_BATCH_INTERVAL", "500");
    final String skipTlsVerify = getEnvOrDefault("SPLUNK_SKIPTLSVERIFY", "false");

    final LayoutComponentBuilder patternLayout =
        builder.newLayout("PatternLayout").addAttribute("pattern", "%msg");

    final AppenderComponentBuilder splunkAppender =
        builder
            .newAppender("Splunk", "SplunkHttp")
            .addAttribute("url", splunkUrl)
            .addAttribute("token", splunkToken)
            .addAttribute("host", host)
            .addAttribute("index", splunkIndex)
            .addAttribute("source", splunkSource)
            .addAttribute("sourcetype", splunkSourcetype)
            .addAttribute("messageFormat", "text")
            .addAttribute("batch_size_bytes", batchSizeBytes)
            .addAttribute("batch_size_count", batchSizeCount)
            .addAttribute("batch_interval", batchInterval)
            .addAttribute("disableCertificateValidation", skipTlsVerify)
            .add(patternLayout);

    builder.add(splunkAppender);
  }

  private static void addLoggerFilters(final ConfigurationBuilder<BuiltConfiguration> builder) {
    // Disable Log4j2 internal status logger
    builder.add(
        builder.newLogger("org.apache.logging.log4j.status.StatusLogger", Level.OFF));

    // DNS timer task filter - suppress "Refreshing DNS records with ..." messages
    builder.add(
        builder
            .newLogger("org.apache.tuweni.discovery.DNSTimerTask", Level.INFO)
            .add(
                builder
                    .newFilter("RegexFilter", "DENY", "NEUTRAL")
                    .addAttribute("regex", "Refreshing DNS records with .*")));

    // DNS resolver filter - suppress "DNS query error with ..." messages
    builder.add(
        builder
            .newLogger("org.apache.tuweni.discovery.DNSResolver", Level.INFO)
            .add(
                builder
                    .newFilter("RegexFilter", "DENY", "NEUTRAL")
                    .addAttribute("regex", "DNS query error with .*")));

    // Vertx DNS exception filter - suppress "DNS query error occurred:..." messages
    builder.add(
        builder
            .newLogger("io.vertx.core.dns.DnsException", Level.INFO)
            .add(
                builder
                    .newFilter("RegexFilter", "DENY", "NEUTRAL")
                    .addAttribute("regex", "DNS query error occurred:.*")));

    // Invalid transaction removal marker filter
    builder.add(
        builder
            .newLogger("org.hyperledger.besu.ethereum.eth.transactions", Level.INFO)
            .add(
                builder
                    .newFilter("MarkerFilter", "DENY", "NEUTRAL")
                    .addAttribute("marker", "INVALID_TX_REMOVED")));

    // OpenTelemetry B3 propagation filter
    builder.add(
        builder
            .newLogger(
                "io.opentelemetry.extension.trace.propagation.B3PropagatorExtractorMultipleHeaders",
                Level.INFO)
            .add(
                builder
                    .newFilter("RegexFilter", "DENY", "NEUTRAL")
                    .addAttribute("regex", "Invalid TraceId in B3 header:.*")));

    // Bonsai worldstate stack trace filter
    builder.add(
        builder
            .newLogger(
                "org.hyperledger.besu.ethereum.trie.pathbased.bonsai.storage.BonsaiSnapshotWorldStateKeyValueStorage",
                Level.INFO)
            .add(
                builder
                    .newFilter("StackTraceMatchFilter", "DENY", "NEUTRAL")
                    .addAttribute("stackContains", "BlockTransactionSelector")
                    .addAttribute("messageEquals", "Attempting to access closed worldstate")));
  }

  private static Level parseLevel(final String logLevel) {
    if (logLevel == null || logLevel.isEmpty()) {
      return Level.INFO;
    }
    return Level.getLevel(logLevel.toUpperCase(java.util.Locale.ROOT));
  }

  private static String getEnvOrDefault(final String envVar, final String defaultValue) {
    final String value = System.getenv(envVar);
    return value != null ? value : defaultValue;
  }

  private static String getHost() {
    String host = System.getenv("HOST");
    if (host == null) {
      // Try Docker container ID
      final String containerId = System.getProperty("docker.containerId");
      if (containerId != null) {
        host = containerId;
      } else {
        // Fall back to hostname
        try {
          host = java.net.InetAddress.getLocalHost().getHostName();
        } catch (java.net.UnknownHostException e) {
          host = "localhost";
        }
      }
    }
    return host;
  }
}

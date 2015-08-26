package org.sagebionetworks.bridge.config;

import static com.google.common.base.Preconditions.checkNotNull;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;
import java.util.regex.Pattern;

import com.google.common.base.StandardSystemProperty;
import com.google.common.base.Strings;
import com.google.common.collect.Lists;

/**
 * Config backed by Java properties.
 */
public class PropertiesConfig implements Config {

    /**
     * Default user when user is not specified in the config.
     */
    public static final String DEFAULT_USER = StandardSystemProperty.USER_NAME.value();

    /**
     * Default environment when environment is not specified in the config.
     */
    public static final Environment DEFAULT_ENV = Environment.LOCAL;

    /**
     * Comma surrounded by optional whitespace. Default delimiter to separate a list of values.
     */
    public static final String DEFAULT_LIST_DELIMITER = "\\s*,\\s*";

    static final String USER_KEY = "bridge.user";
    static final String ENV_KEY = "bridge.env";

    private final String user;
    private final Environment environment;
    private final Properties properties;
    private final Pattern delimiter;

    /**
     * Loads config from a template file.
     * <p>
     * The config template should contain all the config entries. The values can be default values,
     * or, if sensitive, dummy values. The values on the template file are to be overwritten by
     * environment variables and/or system properties in that order.
     * <p>
     * The config template file can be kept in the source code. Then its path can be obtained by
     * <code>Paths.get(getClass().getClassLoader().getResource("relative/path/to/config").getPath());</code>.
     *
     * @param configTemplate
     *            Path to the config template file. 
     */
    public PropertiesConfig(final Path configTemplate) throws IOException {
        this(configTemplate, null, DEFAULT_LIST_DELIMITER);
    }

    /**
     * Loads config from a template file and a local config file.
     * <p>
     * The config template should contain all the config entries. The values can be default values,
     * or, if sensitive, dummy values. The values on the template file are to be overwritten by
     * those of the local config file, environment variables and/or system properties in that order.
     * <p>
     * The config template file can be kept in the source code. Then its path can be obtained by
     * <code>Paths.get(getClass().getClassLoader().getResource("relative/path/to/config").getPath());</code>.
     * <p>
     * The local config file can be kept in the user's home directory. Then its path can be obtained
     * by <code>Paths.get(System.getProperty("user.home") + "/path/to/config");</code>.
     *
     * @param configTemplate
     *            Path to the config template file in the source code.
     *
     * @param userConfig
     *            Path to the local config file.
     */
    public PropertiesConfig(final Path configTemplate, final Path userConfig) throws IOException {
        this(configTemplate, userConfig, DEFAULT_LIST_DELIMITER);
    }

    /**
     * Loads config from a template file and a local config file.
     * <p>
     * The config template should contain all the config entries. The values can be default values,
     * or, if sensitive, dummy values. The values on the template file are to be overwritten by
     * those of the local config file, environment variables and/or system properties in that order.
     * <p>
     * The config template file can be kept in the source code. Then its path can be obtained by
     * <code>Paths.get(getClass().getClassLoader().getResource("relative/path/to/config").getPath());</code>.
     * <p>
     * The local config file can be kept in the user's home directory. Then its path can be obtained
     * by <code>Paths.get(System.getProperty("user.home") + "/path/to/config");</code>.
     *
     * @param configTemplate
     *            Path to the config template file in the source code.
     *
     * @param userConfig
     *            Path to the local config file.
     *
     * @param delimiterRegex
     *            The regular expression for the delimiter that is used to
     *            separate a list of values.
     */
    public PropertiesConfig(final Path configTemplate, final Path localConfig,
            final String delimiterRegex) throws IOException {
        checkNotNull(configTemplate);
        checkNotNull(delimiterRegex);
        final Properties properties = new Properties();
        try (final Reader templateReader = Files.newBufferedReader(configTemplate)) {
            properties.load(templateReader);
        }
        if (localConfig != null) {
            try (final Reader localReader = Files.newBufferedReader(localConfig)) {
                properties.load(localReader);
            }
        }
        user = readUser(properties);
        environment = readEnvironment(properties);
        this.properties = new Properties(collapse(properties, environment.name().toLowerCase()));
        delimiter = Pattern.compile(delimiterRegex);
    }

    @Override
    public String getUser() {
        return user;
    }

    @Override
    public Environment getEnvironment() {
        return environment;
    }

    @Override
    public String get(final String key) {
        checkNotNull(key);
        return properties.getProperty(key);
    }

    @Override
    public int getInt(final String key) {
        checkNotNull(key);
        return Integer.parseInt(properties.getProperty(key));
    }

    @Override
    public List<String> getList(final String key) {
        checkNotNull(key);
        final String property = properties.getProperty(key);
        return Lists.newArrayList(delimiter.split(property));
    }

    private final ConfigReader envReader = new ConfigReader() {
        @Override
        public String read(String name) {
            try {
                // Change to a valid environment variable name
                name = name.toUpperCase().replace('.', '_');
                return System.getenv(name);
            } catch (SecurityException e) {
                throw new GetEnvException(name, e);
            }
        }
    };

    private final ConfigReader sysReader = new ConfigReader() {
        @Override
        public String read(String name) {
            try {
                return System.getProperty(name);
            } catch (SecurityException e) {
                throw new GetSystemPropertyException(name, e);
            }
        }
    };

    private String readUser(final Properties properties) {
        String user = read(USER_KEY, properties);
        if (Strings.isNullOrEmpty(user)) {
            return DEFAULT_USER;
        }
        return user;
    }

    private Environment readEnvironment(final Properties properties) {
        final String envName = read(ENV_KEY, properties);
        if (Strings.isNullOrEmpty(envName)) {
            return DEFAULT_ENV;
        }
        for (Environment env : Environment.values()) {
            if (env.name().toLowerCase().equals(envName)) {
                return env;
            }
        }
        throw new InvalidEnvironmentException(envName);
    }

    private String read(final String key, final Properties properties) {
        final String sysVal = sysReader.read(key);
        if (sysVal != null) {
            return sysVal;
        }
        final String envVal = envReader.read(key);
        if (envVal != null) {
            return envVal;
        }
        return properties.getProperty(key);
    }

    /**
     * Collapses the properties into new properties relevant to the current
     * environment. 1) Default properties usually bundled in the code base as
     * resources. 2) Overwrite with properties read from the user's home
     * directory 3) Merge the properties to the current environment 4) Overwrite
     * with properties read from the environment variables. 5) Overwrite with
     * properties read from the command-line arguments.
     */
    private Properties collapse(final Properties properties, final String envName) {
        Properties collapsed = new Properties();
        // Read the default properties
        for (final String key : properties.stringPropertyNames()) {
            if (isDefaultProperty(key)) {
                collapsed.setProperty(key, properties.getProperty(key));
            }
        }
        // Overwrite with properties for the current environment
        for (final String key : properties.stringPropertyNames()) {
            if (key.startsWith(envName + ".")) {
                String strippedName = key.substring(envName.length() + 1);
                collapsed.setProperty(strippedName, properties.getProperty(key));
            }
        }
        // Overwrite with environment variables and system properties
        for (final String key : properties.stringPropertyNames()) {
            String value = sysReader.read(key);
            if (value == null) {
                value = envReader.read(key);
            }
            if (value != null) {
                collapsed.setProperty(key, value);
            }
        }
        return collapsed;
    }

    /**
     * When the property is not bound to a particular environment.
     */
    private boolean isDefaultProperty(final String propName) {
        for (Environment env : Environment.values()) {
            String envPrefix = env.name().toLowerCase() + ".";
            if (propName.startsWith(envPrefix)) {
                return false;
            }
        }
        return true;
    }
}

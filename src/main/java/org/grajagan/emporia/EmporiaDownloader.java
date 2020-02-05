package org.grajagan.emporia;

import joptsimple.OptionParser;
import joptsimple.OptionSet;
import joptsimple.OptionSpec;
import lombok.extern.log4j.Log4j2;
import org.apache.commons.configuration.Configuration;
import org.apache.commons.configuration.ConfigurationException;
import org.apache.commons.configuration.PropertiesConfiguration;
import org.glassfish.jersey.client.ClientConfig;
import org.grajagan.aws.AuthTokenClientRequestFilter;
import org.grajagan.aws.CognitoAuthenticationManager;
import org.grajagan.emporia.influxdb.InfluxDBLoader;
import org.grajagan.emporia.model.Channel;
import org.grajagan.emporia.model.Customer;
import org.grajagan.emporia.model.Device;
import org.grajagan.emporia.model.Readings;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSession;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import javax.ws.rs.client.Client;
import javax.ws.rs.client.ClientBuilder;
import javax.ws.rs.client.Invocation;
import javax.ws.rs.client.WebTarget;
import javax.ws.rs.core.Response;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Paths;
import java.security.SecureRandom;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static java.util.Arrays.asList;

@Log4j2
public class EmporiaDownloader {
    private static final String HELP_ARG = "help";

    private static final String CONFIGURATION_FILE = "config";

    private static final String REGION = "region";
    private static final String CLIENTAPP_ID = "clientapp-id";
    private static final String POOL_ID = "pool-id";
    private static final String USERNAME = "username";
    private static final String PASSWORD = "password";

    private static final String CUSTOMER_EMAIL = "customer";

    private static final String INFLUX_URL = "influx-url";
    private static final String INFLUX_PORT = "influx-port";
    private static final String INFLUX_USER = "influx-user";
    private static final String INFLUX_PASS = "influx-password";
    private static final String INFLUX_DB = "influx-db";
    private static final String DISABLE_INFLUX = "disable-influx";

    private static final String DEFAULT_CONFIGURATION_FILE =
            Paths.get("config.properties").toAbsolutePath().toString();

    public static final String DEFAULT_LOCAL_HOST = "localhost";
    public static final String DEFAULT_INFLUX_URL = "http://" + DEFAULT_LOCAL_HOST;
    public static final int DEFAULT_INFLUX_PORT = 8086;
    public static final String DEFAULT_INFLUX_DB = "electricity";

    public static final String DEFAULT_LOG_FILE =
            Paths.get("application.log").toAbsolutePath().toString();

    public static final List<String> REQUIRED_PARAMETERS = new ArrayList<>();

    static {
        REQUIRED_PARAMETERS
                .addAll(Arrays.asList(REGION, CLIENTAPP_ID, POOL_ID, USERNAME, PASSWORD));
    }

    private static final String API_URL = "https://api.emporiaenergy.com";

    private Configuration configuration;

    private Client client;

    private Map<Channel, Instant> lastDataPoint = new HashMap<>();

    public static void main(String[] argv) throws Exception {

        OptionParser parser = new OptionParser() {
            {
                accepts(HELP_ARG, "display help text");

                accepts(CONFIGURATION_FILE,
                        "configuration file [" + DEFAULT_CONFIGURATION_FILE + "] (CLI "
                                + "parameters override configured parameters!)").withRequiredArg()
                        .ofType(String.class).defaultsTo(DEFAULT_CONFIGURATION_FILE);

                accepts(REGION, "AWS region").withRequiredArg().ofType(String.class);
                accepts(CLIENTAPP_ID, "AWS client ID").withRequiredArg().ofType(String.class);
                accepts(POOL_ID, "AWS user pool ID").withRequiredArg().ofType(String.class);

                accepts(USERNAME, "username").withRequiredArg().ofType(String.class);
                accepts(PASSWORD, "password").withRequiredArg().ofType(String.class);

                accepts(CUSTOMER_EMAIL, "customer email [defaults to username]").withRequiredArg()
                        .ofType(String.class);

                accepts(INFLUX_URL, "InfluxDB server URL").withRequiredArg().ofType(String.class)
                        .defaultsTo(DEFAULT_INFLUX_URL);
                accepts(INFLUX_PORT, "InfluxDB server port").withRequiredArg()
                        .ofType(Integer.class).defaultsTo(DEFAULT_INFLUX_PORT);
                accepts(INFLUX_USER, "InfluxDB server username").withRequiredArg()
                        .ofType(String.class);
                accepts(INFLUX_PASS, "InfluxDB server password").withRequiredArg()
                        .ofType(String.class);
                accepts(INFLUX_DB, "InfluxDB database").withRequiredArg().ofType(String.class)
                        .defaultsTo(DEFAULT_INFLUX_DB);
                accepts(DISABLE_INFLUX, "disable the uploading to InfluxDB");

                accepts(LoggingConfigurator.LOGFILE, "log to this file [" + DEFAULT_LOG_FILE + "]")
                        .withOptionalArg().defaultsTo(DEFAULT_LOG_FILE);

                acceptsAll(asList("d", LoggingConfigurator.DEBUG), "enable debug messages.");
                acceptsAll(asList("q", LoggingConfigurator.QUIET),
                        "do not print any messages to the console except for errors.");
            }
        };

        OptionSet options = parser.parse(argv);
        if (options.has(HELP_ARG)) {
            printHelp(parser);
            System.exit(0);
        }

        if (!options.has(DISABLE_INFLUX)) {
            REQUIRED_PARAMETERS.add(INFLUX_DB);
            REQUIRED_PARAMETERS.add(INFLUX_PORT);
        }

        Configuration configuration = null;
        try {
            configuration = getConfiguration(options);
        } catch (ConfigurationException e) {
            printHelp(parser);
            System.exit(1);
        }

        LoggingConfigurator.configure(configuration);

        EmporiaDownloader downloader = new EmporiaDownloader(configuration);
        downloader.run();
    }

    private static void printHelp(OptionParser parser) {
        try {
            parser.printHelpOn(System.out);
        } catch (IOException e) {
            log.error(e.getMessage(), e);
        }
    }

    protected static Configuration getConfiguration(OptionSet optionSet)
            throws ConfigurationException {
        Configuration config =
                new PropertiesConfiguration(optionSet.valueOf(CONFIGURATION_FILE).toString());

        for (OptionSpec<?> optionSpec : optionSet.specs()) {
            String property = optionSpec.options().get(optionSpec.options().size() - 1);
            if (optionSet.valuesOf(optionSpec).size() == 0) {
                config.addProperty(property, true);
                continue;
            }

            for (Object value : optionSet.valuesOf(optionSpec)) {
                config.addProperty(property, value);
            }
        }

        for (String option : REQUIRED_PARAMETERS) {
            if (!config.containsKey(option)) {
                Object value = optionSet.valueOf(option);
                if (value == null) {
                    throw new ConfigurationException("Missing parameter " + option);
                }
                config.addProperty(option, value);
            }
        }

        return config;
    }

    protected EmporiaDownloader(Configuration configuration) {
        this.configuration = configuration;
    }

    protected void run() {
        log.info("Starting run!");
        CognitoAuthenticationManager authenticationManager =
                CognitoAuthenticationManager.builder().username(configuration.getString(USERNAME))
                        .password(configuration.getString(PASSWORD))
                        .poolId(configuration.getString(POOL_ID))
                        .region(configuration.getString(REGION))
                        .clientId(configuration.getString(CLIENTAPP_ID)).build();

        ClientConfig clientConfig = new ClientConfig();
        clientConfig.register(AuthTokenClientRequestFilter.class);
        clientConfig.property(AuthTokenClientRequestFilter.AUTHENTICATION_MANAGER,
                authenticationManager);

        client = initClient(clientConfig);
        Customer customer = getCustomer(configuration.containsKey(CUSTOMER_EMAIL) ?
                configuration.getString(CUSTOMER_EMAIL) : configuration.getString(USERNAME));

        if (customer.getDevices() == null || customer.getDevices().isEmpty()) {
            log.warn("Customer " + customer + " has no devices!");
            System.exit(0);
        }

        InfluxDBLoader influxDBLoader = null;
        if (!configuration.containsKey(DISABLE_INFLUX)) {
            URI influxDbUri = null;
            try {
                influxDbUri = new URI((String) configuration.getString(INFLUX_URL));
                influxDbUri = new URI(influxDbUri.getScheme(), influxDbUri.getUserInfo(),
                        influxDbUri.getHost(), configuration.getInt(INFLUX_PORT),
                        influxDbUri.getPath(), influxDbUri.getQuery(), influxDbUri.getFragment());
                influxDBLoader = new InfluxDBLoader(influxDbUri.toURL(),
                        configuration.getString(INFLUX_USER), configuration.getString(INFLUX_PASS),
                        configuration.getString(INFLUX_DB));
            } catch (Exception e) {
                log.error("Cannot instantiate InfluxDBLoader", e);
            }

            InfluxDBLoader finalInfluxDBLoader = influxDBLoader;
            Runtime.getRuntime().addShutdownHook(new Thread() {
                @Override
                public void run() {
                    finalInfluxDBLoader.writeToDB();
                }
            });

            for (Device device : customer.getDevices()) {
                loadChannelData(device, influxDBLoader);
                for (Device attachedDevice : device.getDevices()) {
                    loadChannelData(attachedDevice, influxDBLoader);
                }
            }
        }

        while (true) {
            for (Device device : customer.getDevices()) {
                processDevice(device, influxDBLoader);
                for (Device attachedDevice : device.getDevices()) {
                    processDevice(attachedDevice, influxDBLoader);
                }
            }

            log.info("current write count: " + influxDBLoader.getWritesCount());
            try {
                Thread.sleep(1000 * 60 * 5);
            } catch (InterruptedException e) {
                log.warn("Interrupt: " + e.getMessage());
                break;
            }
        }

        if (influxDBLoader != null) {
            influxDBLoader.writeToDB();
        }
    }

    private void loadChannelData(Device device, InfluxDBLoader influxDBLoader) {
        if (influxDBLoader == null) {
            return;
        }
        for (Channel channel : device.getChannels()) {
            Readings readings = influxDBLoader.load(channel);
            log.debug("last reading for " + channel + ": " + readings);
            lastDataPoint.put(channel, readings.getEnd());
        }
    }

    protected void processDevice(Device device, InfluxDBLoader influxDBLoader) {
        Instant start;
        for (Channel channel : device.getChannels()) {
            if (lastDataPoint.containsKey(channel)) {
                start = lastDataPoint.get(channel);
            } else {
                start = Instant.now().minus(12, ChronoUnit.HOURS);
                lastDataPoint.put(channel, start);
            }

            Instant now = Instant.now();
            Instant end = now.minus(1, ChronoUnit.MILLIS);
            while (end.isBefore(now)) {
                log.debug("channel: " + channel + " " + start + " - " + end);
                Readings readings = getReadings(channel, start, end);
                if (influxDBLoader != null) {
                    influxDBLoader.save(readings);
                }

                log.debug(readings);
                if (readings.getEnd() != null) {
                    start = readings.getEnd();
                    lastDataPoint.put(channel, start);
                    end = start.plus(start.toEpochMilli() - readings.getStart().toEpochMilli(),
                            ChronoUnit.MILLIS);
                } else {
                    break;
                }
            }
        }
    }

    protected Customer getCustomer(String email) {
        String url = API_URL + "/customers?email=" + email;
        Customer customer = (Customer) getObject(url, Customer.class);

        url = API_URL + "/customers/" + customer.getCustomerGid()
                + "/devices?detailed=true&hierarchy=true";

        customer.setDevices(((Customer) getObject(url, Customer.class)).getDevices());
        return customer;
    }

    protected Readings getReadings(Channel channel, Instant start, Instant end) {
        String url = API_URL + "/usage/time?start=%s&end=%s&type=%s&deviceGid=%d&scale"
                + "=%s&unit=%s&channels=%s";
        url = String.format(url, start, end, Readings.DEFAULT_TYPE, channel.getDeviceGid(),
                Readings.DEFAULT_SCALE, Readings.DEFAULT_UNIT, channel.getChannelNum());
        Readings readings = (Readings) getObject(url, Readings.class);
        readings.setChannel(channel);
        return readings;
    }

    protected Object getObject(String url, Class clazz) {
        WebTarget target = client.target(url);
        Invocation.Builder builder = target.request();
        Response response = builder.get();
        return response.readEntity(clazz);
    }

    protected Client initClient(ClientConfig config) {
        SSLContext ctx = null;
        try {
            ctx = SSLContext.getInstance("SSL");
            ctx.init(null, certs, new SecureRandom());
        } catch (Exception e) {
            log.error("Cannot init context!", e);
            System.exit(1);
        }

        Client client = ClientBuilder.newBuilder().withConfig(config).register(JacksonConfig.class)
                .hostnameVerifier(new TrustAllHostNameVerifier()).sslContext(ctx).build();

        return client;
    }

    TrustManager[] certs = new TrustManager[] { new X509TrustManager() {
        @Override
        public X509Certificate[] getAcceptedIssuers() {
            return null;
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType)
                throws CertificateException {
        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType)
                throws CertificateException {
        }
    } };

    public static class TrustAllHostNameVerifier implements HostnameVerifier {
        public boolean verify(String hostname, SSLSession session) {
            return true;
        }
    }
}
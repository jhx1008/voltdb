/* This file is part of VoltDB.
 * Copyright (C) 2008-2017 VoltDB Inc.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with VoltDB.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.voltdb.importclient.kafka10;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.voltcore.logging.VoltLogger;
import org.voltcore.utils.CoreUtils;
import org.voltdb.CLIConfig;
import org.voltdb.client.Client;
import org.voltdb.client.ClientConfig;
import org.voltdb.client.ClientFactory;
import org.voltdb.client.ClientImpl;
import org.voltdb.client.ClientResponse;
import org.voltdb.client.VoltBulkLoader.BulkLoaderSuccessCallback;
import org.voltdb.importer.ImporterLifecycle;
import org.voltdb.utils.BulkLoaderErrorHandler;
import org.voltdb.utils.CSVBulkDataLoader;
import org.voltdb.utils.CSVDataLoader;
import org.voltdb.utils.CSVTupleDataLoader;
import org.voltdb.utils.RowWithMetaData;

/**
 * KafkaConsumer loads data from kafka into voltdb
 * Only csv formatted data is supported at this time.
 * VARBINARY columns are not supported
 */

public class KafkaLoader10 implements ImporterLifecycle {
    private static final VoltLogger m_log = new VoltLogger("KAFKALOADER10");
    private static final String KEY_DESERIALIZER = ByteArrayDeserializer.class.getName();
    private static final String VALUE_DESERIALIZER = ByteArrayDeserializer.class.getName();

    private Kafka10LoaderCLIArguments m_cliOptions;
    private final static AtomicLong m_failedCount = new AtomicLong(0);
    private CSVDataLoader m_loader = null;
    private Client m_client = null;
    private ExecutorService m_executorService = null;
    private final AtomicBoolean m_shutdown = new AtomicBoolean(false);
    private List<Kafka10ExternalConsumerRunner> m_consumers;
    private final long pollTimedWaitInMilliSec = Integer.getInteger("KAFKALOADER_POLLED_WAIT_MILLI_SECONDS", 1000); // 1 second NEEDSWORK
    private ExecutorService m_callbackExecutor = null;

    private volatile boolean m_stopping = false;

    public KafkaLoader10(Kafka10LoaderCLIArguments options) {
        m_cliOptions = options;
    }

    @Override
    public boolean shouldRun() {
        return !m_stopping;
    }

    @Override
    public void stop() {
        m_stopping = true;
    }

    @Override
    public boolean hasTransaction() {
        return false;
    }

    private void shutdownExecutorNow() {
        if (m_executorService == null) return;
        try {
            m_executorService.shutdownNow();
            m_executorService.awaitTermination(365, TimeUnit.DAYS);
        } catch (Throwable ignore) {
        } finally {
            m_executorService = null;
        }
    }

    private void closeLoader() {
        if (m_loader == null) return;
        try {
            m_loader.close();
            m_loader = null;
        } catch (Throwable ignore) {
        } finally {
            m_loader = null;
        }
    }

    private void closeClient() {
        if (m_client == null) return;
        try {
            m_client.close();
        } catch (Throwable ignore) {
        } finally {
            m_client = null;
        }
    }

    void close() {
        shutdownExecutorNow();
        closeLoader();
        closeClient();
    }


    class KafkaBulkLoaderCallback implements BulkLoaderErrorHandler, BulkLoaderSuccessCallback {

        @Override
        public void success(Object rowHandle, ClientResponse response) {
            RowWithMetaData metaData = (RowWithMetaData) rowHandle;
            try {
                metaData.procedureCallback.clientCallback(response);
            }
            catch (Exception e) {
                e.printStackTrace();
            }
        }
        @Override
        public boolean handleError(RowWithMetaData metaData, ClientResponse response, String error) {
            if (m_cliOptions.maxerrors <= 0) return false;
            if (response != null) {
                byte status = response.getStatus();
                if (status != ClientResponse.SUCCESS) {
                    m_log.error("Failed to Insert Row: " + metaData.rawLine);
                    long fc = m_failedCount.incrementAndGet();
                    if ((m_cliOptions.maxerrors > 0 && fc > m_cliOptions.maxerrors)
                            || (status != ClientResponse.USER_ABORT && status != ClientResponse.GRACEFUL_FAILURE)) {
                        notifyShutdown();
                        return true;
                    }
                }
            }
            return false;
        }

        @Override
        public boolean hasReachedErrorLimit() {
            long fc = m_failedCount.get();
            return (m_cliOptions.maxerrors > 0 && fc > m_cliOptions.maxerrors);
        }
    }

    private Properties kafkaConfigProperties() throws IOException {
        //Get group id which should be unique for table so as to keep offsets clean for multiple runs.
        String groupId = "voltdb-" + (m_cliOptions.useSuppliedProcedure ? m_cliOptions.procedure : m_cliOptions.table);

        Properties props = new Properties();
        if (m_cliOptions.config.trim().isEmpty()) {
            props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
            // default liveness check of consumer is 5 minutes
        } else {
            props.load(new FileInputStream(new File(m_cliOptions.config)));
            //Get GroupId from property if present and use it.
            groupId = props.getProperty("group.id", groupId);

            // get kafka broker connections from properties file if present - supplied brokers, if any, overrides
            // the supplied command line
            m_cliOptions.brokers = props.getProperty(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, m_cliOptions.brokers);

            String autoCommit = props.getProperty(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG);
            if (autoCommit != null && !autoCommit.trim().isEmpty() &&
                    !("true".equals(autoCommit.trim().toLowerCase())) ) {
                m_log.warn("Auto commit policy for Kafka loader will be set to \'true\' instead of \'" + autoCommit +"\'");
            }

            if (props.getProperty(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG) == null)
                props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");

            // Only byte array are used by Kafka Loader. If there are any deserializer supplied in config file
            // log warning message about it
            String deserializer = props.getProperty(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG);
            if (deserializer != null && KEY_DESERIALIZER.equals(deserializer.trim()) ) {
                m_log.warn("Key deserializer \'" + deserializer.trim() + "\' not supported. \'"
                        + KEY_DESERIALIZER + "\' will be used for deserializering keys");
            }
            deserializer = props.getProperty(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG);
            if ( deserializer != null && VALUE_DESERIALIZER.equals(deserializer.trim())) {
                m_log.warn("Value deserializer \'" + deserializer.trim() + "\' not supported. \'"
                        + VALUE_DESERIALIZER + "\' will be used for deserializering values");
            }
        }

        // populate/override kafka consumer properties
        props.put(ConsumerConfig.GROUP_ID_CONFIG, groupId);
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, m_cliOptions.brokers);
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class.getName());
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "true");

        return props;
    }

    private ExecutorService getExecutor() throws Exception {

        // NEEDSWORK: We might be able to refactor the properties into the base runner, too.
        Properties props = kafkaConfigProperties();

        // NEEDSWORK: Straighten out properties, this is bogus
        props.setProperty("topics", m_cliOptions.topic);

        Kafka10StreamImporterConfig cfg = new Kafka10StreamImporterConfig(props, null); // NEEDSWORK: Hook up formatter



        // create as many threads equal as number of partitions specified in config
        ExecutorService executor = Executors.newFixedThreadPool(m_cliOptions.getConsumerCount());
        m_consumers = new ArrayList<>();
        try {
            KafkaConsumer<byte[], byte[]> consumer = null;
            for (int i = 0; i < m_cliOptions.getConsumerCount(); i++) {
                consumer = new KafkaConsumer<>(props);
                m_consumers.add(new Kafka10ExternalConsumerRunner(this, cfg, consumer, m_loader));

            }
        } catch (Throwable terminate) {
            m_log.error("Failed creating Kafka consumer ", terminate);
            for (Kafka10ExternalConsumerRunner consumer : m_consumers) {
                // close all consumer connections
                consumer.shutdown();
            }
            return null;
        }

        for (Kafka10ExternalConsumerRunner consumer : m_consumers) {
            executor.submit(consumer);
        }
        return executor;
    }

    // shutdown hook to notify kafka consumer threads of shutdown
    private void notifyShutdown() {
        if (m_shutdown.compareAndSet(false, true)) {
            m_log.info("Kafka consumer shutdown signalled ... ");
            for (Kafka10ExternalConsumerRunner consumer : m_consumers) {
                consumer.shutdown();
            }
        }
    }

    private void processKafkaMessages() throws Exception {
        // Split server list
        final String[] serverlist = m_cliOptions.servers.split(",");

        // If we need to prompt the user for a VoltDB password, do so.
        m_cliOptions.password = CLIConfig.readPasswordIfNeeded(m_cliOptions.user, m_cliOptions.password, "Enter password: ");

        // Create connection
        final ClientConfig clientConfig = new ClientConfig(m_cliOptions.user, m_cliOptions.password, null);
        if (m_cliOptions.ssl != null && !m_cliOptions.ssl.trim().isEmpty()) {
            clientConfig.setTrustStoreConfigFromPropertyFile(m_cliOptions.ssl);
            clientConfig.enableSSL();
        }
        clientConfig.setProcedureCallTimeout(0);
        m_client = getVoltClient(clientConfig, m_cliOptions.getVoltHosts());

        KafkaBulkLoaderCallback kafkaBulkLoaderCallback = new KafkaBulkLoaderCallback();

        if (m_cliOptions.useSuppliedProcedure) {
            m_callbackExecutor = CoreUtils.getSingleThreadExecutor( m_cliOptions.procedure + "-" + Thread.currentThread().getName());
            m_loader = new CSVTupleDataLoader((ClientImpl) m_client, m_cliOptions.procedure, kafkaBulkLoaderCallback, m_callbackExecutor, kafkaBulkLoaderCallback);
        } else {
            m_loader = new CSVBulkDataLoader((ClientImpl) m_client, m_cliOptions.table, m_cliOptions.batch, m_cliOptions.update, kafkaBulkLoaderCallback, kafkaBulkLoaderCallback);
        }
        m_loader.setFlushInterval(m_cliOptions.flush, m_cliOptions.flush);

        if ((m_executorService = getExecutor()) != null) {
            if (m_cliOptions.useSuppliedProcedure) {
                m_log.info("Kafka Consumer from topic: " + m_cliOptions.topic + " Started using procedure: " + m_cliOptions.procedure);
            } else {
                m_log.info("Kafka Consumer from topic: " + m_cliOptions.topic + " Started for table: " + m_cliOptions.table);
            }
            m_executorService.shutdown();
            m_executorService.awaitTermination(365, TimeUnit.DAYS);
            m_executorService = null;
        }
    }

    /*
     * Create a Volt client from the supplied configuration and list of servers.
     */
    private static Client getVoltClient(ClientConfig config, List<String> hosts) throws Exception {
        final Client client = ClientFactory.createClient(config);
        for (String host : hosts) {
            client.createConnection(host);
        }
        return client;
    }

    /**
     * kafkaloader main
     *
     * @param args
     *
     */
    public static void main(String[] args) {

        final Kafka10LoaderCLIArguments options = new Kafka10LoaderCLIArguments();
        options.parse(KafkaLoader10.class.getName(), args);

        KafkaLoader10 kloader = new KafkaLoader10(options);

        try {
            kloader.processKafkaMessages();
        } catch (Exception e) {
            m_log.error("Failure in KafkaLoader10 ", e);
        } finally {
            kloader.close();
        }

        System.exit(0);
    }

}

/*
 * Copyright 2014-2015 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * A copy of the License is located at
 *
 *  http://aws.amazon.com/apache2.0
 *
 * or in the "license" file accompanying this file. This file is distributed
 * on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */
package com.amazon.titan;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;

import org.apache.commons.configuration.Configuration;
import org.apache.commons.configuration.ConfigurationException;
import org.apache.commons.configuration.PropertiesConfiguration;

import com.amazon.titan.diskstorage.dynamodb.BackendDataModel;
import com.amazon.titan.diskstorage.dynamodb.Client;
import com.amazon.titan.diskstorage.dynamodb.Constants;
import com.amazon.titan.diskstorage.dynamodb.DynamoDBDelegate;
import com.amazonaws.services.dynamodbv2.model.DeleteTableRequest;
import com.amazonaws.services.dynamodbv2.model.ListTablesResult;
import com.amazonaws.services.dynamodbv2.model.ResourceNotFoundException;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.thinkaurelius.titan.core.TitanFactory;
import com.thinkaurelius.titan.core.TitanGraph;
import com.thinkaurelius.titan.diskstorage.BackendException;
import com.thinkaurelius.titan.diskstorage.configuration.BasicConfiguration;
import com.thinkaurelius.titan.diskstorage.configuration.BasicConfiguration.Restriction;
import com.thinkaurelius.titan.diskstorage.configuration.WriteConfiguration;
import com.thinkaurelius.titan.diskstorage.configuration.backend.CommonsConfiguration;
import com.thinkaurelius.titan.graphdb.configuration.GraphDatabaseConfiguration;

/**
 *
 * @author Alexander Patrikalakis
 * @author Michael Rodaitis
 *
 */
public class TestGraphUtil {
    private static final TestGraphUtil instance;
    static {
        instance = new TestGraphUtil();
    }
    public static TestGraphUtil instance() {
        return instance;
    }

    private final int dynamoDBPartitions;
    private final int controlPlaneRate;
    private final boolean unlimitedIops;
    private final int provisionedReadAndWriteTps;
    private final File propertiesFile;

    private TestGraphUtil() {
        dynamoDBPartitions = Integer.valueOf(System.getProperty("dynamodb-partitions", String.valueOf(1)));
        Preconditions.checkArgument(dynamoDBPartitions > 0);
        provisionedReadAndWriteTps = 750 * dynamoDBPartitions;
        unlimitedIops = Boolean.valueOf(System.getProperty("dynamodb-unlimited-iops", String.valueOf(Boolean.TRUE)));
        controlPlaneRate = Integer.valueOf(System.getProperty("dynamodb-control-plane-rate", String.valueOf(1)));
        Preconditions.checkArgument(controlPlaneRate > 0);
        //This is a configuration file for test code. not part of production applications.
        //no validation necessary.
        propertiesFile = new File(System.getProperty("properties-file", "src/test/resources/dynamodb-local.properties"));
        Preconditions.checkArgument(propertiesFile.exists());
        Preconditions.checkArgument(propertiesFile.isFile());
        Preconditions.checkArgument(propertiesFile.canRead());
    }

    public boolean isUnlimitedIops() {
        return unlimitedIops;
    }

    public TitanGraph openGraph(BackendDataModel backendDataModel) {
        Configuration config = createTestGraphConfig(backendDataModel);
        TitanGraph graph = TitanFactory.open(config);
        return graph;
    }

    public TitanGraph openGraphWithElasticSearch(BackendDataModel backendDataModel) {
        Configuration config = createTestGraphConfig(backendDataModel);
        addElasticSearchConfig(config);
        TitanGraph graph = TitanFactory.open(config);
        return graph;
    }

    public void tearDownGraph(TitanGraph graph) throws BackendException {
        if(null != graph) {
            graph.close();
        }
        cleanUpTables();
    }

    @VisibleForTesting // used in ClientTest.java
    Client createClient() {
        return new Client(new BasicConfiguration(GraphDatabaseConfiguration.ROOT_NS,
                                                 new CommonsConfiguration(loadProperties()),
                                                 Restriction.NONE));
    }

    public Configuration loadProperties() {
        PropertiesConfiguration storageConfig;
        try {
            storageConfig = new PropertiesConfiguration(propertiesFile);
        } catch (ConfigurationException e) {
            throw new RuntimeException(e);
        }
        return storageConfig;
    }

    private static Configuration addElasticSearchConfig(Configuration config) {
        File tempSearchIndexDirectory;
        try {
            tempSearchIndexDirectory = Files.createTempDirectory(null /*prefix*/).toFile();
        } catch (IOException e) {
            throw new IllegalStateException("unable to create search index temp dir", e);
        }

        final Configuration search = config.subset("index").subset("search");
        search.setProperty("backend", "elasticsearch");
        search.setProperty("directory", tempSearchIndexDirectory.getAbsolutePath());
        final Configuration es = search.subset("elasticsearch");
        es.setProperty("client-only", "false");
        es.setProperty("local-mode", "true");
        return config;
    }

    public Configuration createTestGraphConfig(BackendDataModel backendDataModel) {
        final String dataModelName = backendDataModel.name();

        final Configuration properties = createTestConfig(backendDataModel);

        final String storesNsPrefix = "storage.dynamodb.stores.";
        for (String store : Constants.REQUIRED_BACKEND_STORES) {
            configureStore(dataModelName, provisionedReadAndWriteTps, properties, unlimitedIops, storesNsPrefix + store);
        }

        return properties;
    }
    
    private Configuration createTestConfig(BackendDataModel backendDataModel) {
        final Configuration properties = loadProperties();
        final Configuration dynamodb = properties.subset("storage").subset("dynamodb");
        dynamodb.setProperty("prefix", backendDataModel.name() /*prefix*/);
        dynamodb.setProperty("control-plane-rate", controlPlaneRate);
        return properties;
    }

    private static void configureStore(String dataModelName, final int tps,
        final Configuration config, boolean unlimitedIops, String prefix) {
        final String prefixPeriod = prefix + ".";
        config.setProperty(prefixPeriod + Constants.STORES_DATA_MODEL.getName(), dataModelName);
        config.setProperty(prefixPeriod + Constants.STORES_SCAN_LIMIT.getName(), 10000);
        config.setProperty(prefixPeriod + Constants.STORES_CAPACITY_READ.getName(), tps);
        config.setProperty(prefixPeriod + Constants.STORES_READ_RATE_LIMIT.getName(), unlimitedIops ? Integer.MAX_VALUE : tps);
        config.setProperty(prefixPeriod + Constants.STORES_CAPACITY_WRITE.getName(), tps);
        config.setProperty(prefixPeriod + Constants.STORES_WRITE_RATE_LIMIT.getName(), unlimitedIops ? Integer.MAX_VALUE : tps);
    }

    private static void configureStore(String dataModelName, final int tps,
        final WriteConfiguration config, boolean unlimitedIops, String prefix) {
        final String prefixPeriod = prefix + ".";
        config.set(prefixPeriod + Constants.STORES_DATA_MODEL.getName(), dataModelName);
        config.set(prefixPeriod + Constants.STORES_SCAN_LIMIT.getName(), 10000);
        config.set(prefixPeriod + Constants.STORES_CAPACITY_READ.getName(), tps);
        config.set(prefixPeriod + Constants.STORES_READ_RATE_LIMIT.getName(), unlimitedIops ? Integer.MAX_VALUE : tps);
        config.set(prefixPeriod + Constants.STORES_CAPACITY_WRITE.getName(), tps);
        config.set(prefixPeriod + Constants.STORES_WRITE_RATE_LIMIT.getName(), unlimitedIops ? Integer.MAX_VALUE : tps);
    }

    public WriteConfiguration getStoreConfig(BackendDataModel model, List<String> storeNames) {
        return appendClusterPartitionsAndStores(model, new CommonsConfiguration(TestGraphUtil.instance().createTestConfig(model)), storeNames, 1 /*partitions*/);
    }
    
    public WriteConfiguration appendStoreConfig(BackendDataModel model, WriteConfiguration config, List<String> storeNames) {
        final Configuration baseconfig = createTestConfig(model);
        final Iterator<String> it = baseconfig.getKeys();
        while(it.hasNext()) {
            final String key = it.next();
            config.set(key, baseconfig.getProperty(key));
        }
        return appendClusterPartitionsAndStores(model, config, storeNames, 1 /*titanClusterPartitions*/);
    }

    public WriteConfiguration graphConfigWithClusterPartitionsAndExtraStores(BackendDataModel model, 
            final List<String> extraStoreNames, int titanClusterPartitions) {
        return appendClusterPartitionsAndStores(model, new CommonsConfiguration(TestGraphUtil.instance().createTestGraphConfig(model)),
            extraStoreNames, titanClusterPartitions);
    }
    
    public WriteConfiguration graphConfig(BackendDataModel model) {
        return graphConfigWithClusterPartitionsAndExtraStores(model, Collections.emptyList(), 1);
    }

    public WriteConfiguration appendClusterPartitionsAndStores(BackendDataModel model, WriteConfiguration config, List<String> storeNames, int titanClusterPartitions) {
        final Configuration baseconfig = createTestConfig(model);
        final Iterator<String> it = baseconfig.getKeys();
        while(it.hasNext()) {
            final String key = it.next();
            config.set(key, baseconfig.getProperty(key));
        }
        
        Preconditions.checkArgument(titanClusterPartitions > 0);
        if(titanClusterPartitions > 1) {
            config.set("cluster.max-partitions", Integer.toString(titanClusterPartitions));
        }

        for(String store : storeNames) {
            final String prefix = "storage.dynamodb.stores." + store;
            TestGraphUtil.configureStore(model.name(), provisionedReadAndWriteTps, config, isUnlimitedIops(), prefix);
        }

        return config;
    }

    private static void deleteAllTables(String prefix, DynamoDBDelegate delegate) throws BackendException {
        final ListTablesResult result = delegate.listAllTables();
        for(String tableName : result.getTableNames()) {
            if(prefix != null && !tableName.startsWith(prefix)) {
                continue;
            }
            try {
                delegate.deleteTable(new DeleteTableRequest().withTableName(tableName));
            } catch (ResourceNotFoundException e) {
            }
        }
    }

    public void cleanUpTables() throws BackendException {
        final Client client = instance().createClient();
        deleteAllTables(null /*prefix - delete all tables*/, client.delegate());
        client.delegate().shutdown();
    }
}

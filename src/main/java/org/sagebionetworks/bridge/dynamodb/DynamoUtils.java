package org.sagebionetworks.bridge.dynamodb;

import static com.google.common.base.Preconditions.checkNotNull;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.sagebionetworks.bridge.config.Config;
import org.sagebionetworks.bridge.config.Environment;

import com.amazonaws.services.dynamodbv2.AmazonDynamoDB;
import com.amazonaws.services.dynamodbv2.datamodeling.DynamoDBMapper;
import com.amazonaws.services.dynamodbv2.datamodeling.DynamoDBMapperConfig;
import com.amazonaws.services.dynamodbv2.datamodeling.DynamoDBMapperConfig.TableNameOverride;
import com.amazonaws.services.dynamodbv2.datamodeling.DynamoDBTable;
import com.amazonaws.services.dynamodbv2.model.CreateTableRequest;
import com.amazonaws.services.dynamodbv2.model.DescribeTableRequest;
import com.amazonaws.services.dynamodbv2.model.DescribeTableResult;
import com.amazonaws.services.dynamodbv2.model.GlobalSecondaryIndex;
import com.amazonaws.services.dynamodbv2.model.GlobalSecondaryIndexDescription;
import com.amazonaws.services.dynamodbv2.model.KeySchemaElement;
import com.amazonaws.services.dynamodbv2.model.ListTablesResult;
import com.amazonaws.services.dynamodbv2.model.LocalSecondaryIndex;
import com.amazonaws.services.dynamodbv2.model.LocalSecondaryIndexDescription;
import com.amazonaws.services.dynamodbv2.model.ProvisionedThroughput;
import com.amazonaws.services.dynamodbv2.model.ResourceNotFoundException;
import com.amazonaws.services.dynamodbv2.model.TableDescription;
import com.amazonaws.services.dynamodbv2.model.TableStatus;

public final class DynamoUtils {

    public static TableNameOverride getTableNameOverride(final Class<?> dynamoTable, final Config config) {
        checkNotNull(dynamoTable);
        checkNotNull(config);
        final DynamoDBTable table = dynamoTable.getAnnotation(DynamoDBTable.class);
        if (table == null) {
            throw new IllegalArgumentException("Missing DynamoDBTable annotation for " + dynamoTable.getName());
        }
        final Environment env = config.getEnvironment();
        return new TableNameOverride(env.name().toLowerCase() + "-" + config.getUser() + "-" + table.tableName());
    }

    public static String getTableName(final Class<?> dynamoTable, final Config config) {
        return getTableNameOverride(dynamoTable, config).getTableName();
    }

    /**
     * Gets the mapper with UPDATE behavior for saves and CONSISTENT reads.
     */
    public static DynamoDBMapper getMapper(final Class<?> dynamoTable, final Config config,
            final AmazonDynamoDB dynamoClient) {
        checkNotNull(dynamoTable);
        checkNotNull(config);
        checkNotNull(dynamoClient);
        final DynamoDBMapperConfig mapperConfig = new DynamoDBMapperConfig.Builder()
                .withSaveBehavior(DynamoDBMapperConfig.SaveBehavior.UPDATE)
                .withConsistentReads(DynamoDBMapperConfig.ConsistentReads.CONSISTENT)
                .withTableNameOverride(getTableNameOverride(dynamoTable, config)).build();
        return new DynamoDBMapper(dynamoClient, mapperConfig);
    }

    /**
     * Gets the mapper with UPDATE behavior for saves and EVENTUALLY consistent reads.
     */
    public static DynamoDBMapper getMapperEventually(final Class<?> dynamoTable, final Config config,
            final AmazonDynamoDB dynamoClient) {
        checkNotNull(dynamoTable);
        checkNotNull(config);
        checkNotNull(dynamoClient);
        final DynamoDBMapperConfig mapperConfig = new DynamoDBMapperConfig.Builder()
                .withSaveBehavior(DynamoDBMapperConfig.SaveBehavior.UPDATE)
                .withConsistentReads(DynamoDBMapperConfig.ConsistentReads.EVENTUAL)
                .withTableNameOverride(getTableNameOverride(dynamoTable, config)).build();
        return new DynamoDBMapper(dynamoClient, mapperConfig);
    }

    public static CreateTableRequest getCreateTableRequest(final TableDescription table) {

        checkNotNull(table);

        final CreateTableRequest request = new CreateTableRequest()
                .withTableName(table.getTableName())
                .withKeySchema(table.getKeySchema())
                .withAttributeDefinitions(table.getAttributeDefinitions());

        // ProvisionedThroughputDescription -> ProvisionedThroughput
        final ProvisionedThroughput throughput = new ProvisionedThroughput(
                table.getProvisionedThroughput().getReadCapacityUnits(),
                table.getProvisionedThroughput().getWriteCapacityUnits());
        request.setProvisionedThroughput(throughput);

        // GlobalSecondaryIndexDescription -> GlobalSecondaryIndex
        final List<GlobalSecondaryIndex> globalIndices = new ArrayList<>();
        for (GlobalSecondaryIndexDescription globalIndexDesc : table.getGlobalSecondaryIndexes()) {
            final GlobalSecondaryIndex globalIndex = new GlobalSecondaryIndex()
                    .withIndexName(globalIndexDesc.getIndexName())
                    .withKeySchema(globalIndexDesc.getKeySchema())
                    .withProjection(globalIndexDesc.getProjection())
                    .withProvisionedThroughput(new ProvisionedThroughput(
                            globalIndexDesc.getProvisionedThroughput().getReadCapacityUnits(),
                            globalIndexDesc.getProvisionedThroughput().getWriteCapacityUnits()));
            globalIndices.add(globalIndex);
        }
        if (globalIndices.size() > 0) {
            request.setGlobalSecondaryIndexes(globalIndices);
        }

        // LocalSecondaryIndexDescription -> LocalSecondaryIndex
        final List<LocalSecondaryIndex> localIndices = new ArrayList<>();
        for (LocalSecondaryIndexDescription localIndexDesc : table.getLocalSecondaryIndexes()) {
            final LocalSecondaryIndex localIndex = new LocalSecondaryIndex()
                    .withIndexName(localIndexDesc.getIndexName())
                    .withKeySchema(localIndexDesc.getKeySchema())
                    .withProjection(localIndexDesc.getProjection());
            localIndices.add(localIndex);
        }
        if (localIndices.size() > 0) {
            request.setLocalSecondaryIndexes(localIndices);
        }

        return request;
    }

    /**
     * Compares if the two tables are of the same name. Also compares hash key, range key of the two tables.
     * Throws an exception if there is difference.
     */
    public static void compareSchema(final TableDescription table1, final TableDescription table2) {
        if (table1.getTableName().equals(table2.getTableName())) {
            compareKeySchema(table1, table2);
        }
    }

    /**
     * Compares hash key, range key of the two tables. Throws an exception if there is difference.
     */
    public static void compareKeySchema(final TableDescription table1, final TableDescription table2) {
        List<KeySchemaElement> keySchema1 = table1.getKeySchema();
        List<KeySchemaElement> keySchema2 = table2.getKeySchema();
        compareKeySchema(keySchema1, keySchema2);
    }

    private static void compareKeySchema(final List<KeySchemaElement> keySchema1, final List<KeySchemaElement> keySchema2) {
        if (keySchema1.size() != keySchema2.size()) {
            throw new KeySchemaMismatchException("Key schemas have different number of key elements.");
        }
        final Map<String, KeySchemaElement> keySchemaMap1 = new HashMap<>();
        for (KeySchemaElement ele1 : keySchema1) {
            keySchemaMap1.put(ele1.getAttributeName(), ele1);
        }
        for (KeySchemaElement ele2 : keySchema2) {
            KeySchemaElement ele1 = keySchemaMap1.get(ele2.getAttributeName());
            if (ele1 == null) {
                throw new KeySchemaMismatchException("Missing key " + ele2.getAttributeName() + " in schema 1.");
            }
            if (!ele1.equals(ele2)) {
                throw new KeySchemaMismatchException("Different key schema for key " + ele2.getAttributeName());
            }
        }
    }

    // TODO: Add timeout
    public static void waitForActive(final AmazonDynamoDB dynamo, final String tableName) {
        checkNotNull(dynamo);
        checkNotNull(tableName);
        final DescribeTableRequest request = new DescribeTableRequest(tableName);
        TableDescription table = dynamo.describeTable(request).getTable();
        while (!TableStatus.ACTIVE.name().equalsIgnoreCase(table.getTableStatus())) {
            try {
                Thread.sleep(100L);
            } catch (InterruptedException e) {
                throw new RuntimeException("Shouldn't be interrupted.", e);
            }
            table = dynamo.describeTable(request).getTable();
        }
    }

    // TODO: Add timeout
    public static void waitForDelete(final AmazonDynamoDB dynamo, final String tableName) {
        checkNotNull(dynamo);
        checkNotNull(tableName);
        final DescribeTableRequest request = new DescribeTableRequest(tableName);
        while (true) {
            try {
                Thread.sleep(100L);
            } catch (InterruptedException e) {
                throw new RuntimeException("Shouldn't be interrupted.", e);
            }
            try {
                dynamo.describeTable(request);
            } catch (ResourceNotFoundException e) {
                return;
            }
        }
    }

    public static Map<String, TableDescription> getExistingTables(final AmazonDynamoDB dynamo) {
        checkNotNull(dynamo);
        final Map<String, TableDescription> existingTables = new HashMap<>();
        String lastTableName = null;
        ListTablesResult listTablesResult = dynamo.listTables();
        do {
            for (final String tableName : listTablesResult.getTableNames()) {
                DescribeTableResult describeResult = dynamo.describeTable(new DescribeTableRequest(tableName));
                TableDescription table = describeResult.getTable();
                existingTables.put(tableName, table);
            }
            lastTableName = listTablesResult.getLastEvaluatedTableName();
            if (lastTableName != null) {
                listTablesResult = dynamo.listTables(lastTableName);
            }
        } while (lastTableName != null);
        return existingTables;
    }

    private DynamoUtils() {}
}
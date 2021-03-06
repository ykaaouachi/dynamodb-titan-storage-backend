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
package com.amazon.titan.diskstorage.dynamodb;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.amazon.titan.diskstorage.dynamodb.ExponentialBackoff.Query;
import com.amazonaws.services.dynamodbv2.model.AttributeValue;
import com.amazonaws.services.dynamodbv2.model.ConsumedCapacity;
import com.amazonaws.services.dynamodbv2.model.QueryRequest;
import com.amazonaws.services.dynamodbv2.model.QueryResult;
import com.thinkaurelius.titan.diskstorage.StaticBuffer;
import com.thinkaurelius.titan.diskstorage.BackendException;

/**
 * QueryWorker iterates through pages of DynamoDB Query results.
 *
 * @author Alexander Patrikalakis
 *
 */
public class QueryWorker extends PaginatingTask<QueryRequest, QueryResultWrapper>
{
    private final QueryRequest request;
    private StaticBuffer titanKey;
    private int returnedCount;
    private int scannedCount;
    private List<Map<String, AttributeValue>> items;
    private boolean hasNext;
    private int permitsToConsume;
    private double totalCapacityUnits;

    public QueryWorker(final DynamoDBDelegate delegate, final QueryRequest request, final StaticBuffer titanKey) {
        super(delegate, DynamoDBDelegate.QUERY, request.getTableName());
        this.request = request;
        this.titanKey = titanKey;
        this.returnedCount = 0;
        this.scannedCount = 0;
        this.items = new ArrayList<>();
        this.hasNext = true;
        this.permitsToConsume = 1;
        this.totalCapacityUnits = 0.0;
    }

    @Override
    public QueryResultWrapper next() throws BackendException
    {
        Query backoff = new ExponentialBackoff.Query(request, delegate, permitsToConsume);
        QueryResult result = backoff.runWithBackoff();
        ConsumedCapacity consumedCapacity = result.getConsumedCapacity();
        if (null != consumedCapacity) {
            permitsToConsume = Math.max((int) (consumedCapacity.getCapacityUnits() - 1.0), 1);
            totalCapacityUnits += consumedCapacity.getCapacityUnits();
        }

        if(result.getLastEvaluatedKey() != null && !result.getLastEvaluatedKey().isEmpty()) {
            request.setExclusiveStartKey(result.getLastEvaluatedKey());
        } else {
            markComplete();
        }
        // a update returned count
        returnedCount += result.getCount();

        // b update scanned count
        scannedCount += result.getScannedCount();
        // c add scanned items
        items.addAll(result.getItems());
        return new QueryResultWrapper(titanKey, result);
    }

    @Override
    public boolean hasNext()
    {
        return hasNext;
    }

    public int getScannedCount() {
        return scannedCount;
    }

    public int getReturnedCount() {
        return returnedCount;
    }

    public QueryRequest getRequest() {
        return request;
    }

    protected void markComplete() {
        hasNext = false;
    }

    @Override
    protected QueryResultWrapper getMergedPages() {
        QueryResult mergedDynamoResult = new QueryResult().withItems(getFinalItemList())
                                                          .withCount(returnedCount)
                                                          .withScannedCount(scannedCount)
                                                          .withConsumedCapacity(new ConsumedCapacity()
                                                                                        .withTableName(request.getTableName())
                                                                                        .withCapacityUnits(totalCapacityUnits));
        return new QueryResultWrapper(titanKey, mergedDynamoResult);
    }

    protected List<Map<String, AttributeValue>> getFinalItemList() {
        return this.items;
    }
}

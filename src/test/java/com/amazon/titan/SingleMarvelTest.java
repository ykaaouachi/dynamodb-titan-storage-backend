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

import org.junit.AfterClass;
import org.junit.BeforeClass;

import com.amazon.titan.diskstorage.dynamodb.BackendDataModel;
import com.thinkaurelius.titan.core.TitanGraph;
import com.thinkaurelius.titan.diskstorage.BackendException;

/**
 *
 * @author Matthew Sowders
 * @author Alexander Patrikalakis
 */
public class SingleMarvelTest extends AbstractMarvelTest {

    private static TitanGraph GRAPH;

    @BeforeClass
    public static void setUpGraph() throws Exception {
        GRAPH = TestGraphUtil.instance().openGraph(BackendDataModel.SINGLE);
        AbstractMarvelTest.loadData(GRAPH, 100 /* Number of lines to read from marvel.csv */);
    }

    @AfterClass
    public static void tearDownGraph() throws BackendException {
        TestGraphUtil.instance().tearDownGraph(GRAPH);
    }

    @Override
    protected TitanGraph getGraph() {
        return GRAPH;
    }

}

/*
 * Copyright (c) 2002-2019 "Neo4j,"
 * Neo4j Sweden AB [http://neo4j.com]
 *
 * This file is part of Neo4j.
 *
 * Neo4j is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.neo4j.graphdb;

import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestName;

import java.util.Map;

import org.neo4j.graphdb.spatial.Point;
import org.neo4j.internal.helpers.collection.Iterators;
import org.neo4j.test.mockito.matcher.Neo4jMatchers;
import org.neo4j.test.mockito.mock.SpatialMocks;
import org.neo4j.test.rule.ImpermanentDbmsRule;
import org.neo4j.values.storable.CoordinateReferenceSystem;
import org.neo4j.values.storable.PointValue;
import org.neo4j.values.storable.Values;

import static java.lang.String.format;
import static org.hamcrest.core.IsEqual.equalTo;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.fail;
import static org.neo4j.internal.helpers.collection.Iterators.asSet;
import static org.neo4j.internal.helpers.collection.Iterators.count;
import static org.neo4j.internal.helpers.collection.MapUtil.map;
import static org.neo4j.test.mockito.matcher.Neo4jMatchers.containsOnly;
import static org.neo4j.test.mockito.matcher.Neo4jMatchers.findNodesByLabelAndProperty;
import static org.neo4j.test.mockito.matcher.Neo4jMatchers.hasProperty;
import static org.neo4j.test.mockito.matcher.Neo4jMatchers.isEmpty;
import static org.neo4j.test.mockito.mock.SpatialMocks.mockCartesian;
import static org.neo4j.test.mockito.mock.SpatialMocks.mockCartesian_3D;
import static org.neo4j.test.mockito.mock.SpatialMocks.mockWGS84;
import static org.neo4j.test.mockito.mock.SpatialMocks.mockWGS84_3D;

public class IndexingAcceptanceTest
{
    private static final String LONG_STRING = "a long string that has to be stored in dynamic records";

    @ClassRule
    public static ImpermanentDbmsRule dbRule = new ImpermanentDbmsRule();
    @Rule
    public final TestName testName = new TestName();

    private Label LABEL1;
    private Label LABEL2;
    private Label LABEL3;

    @Before
    public void setupLabels()
    {
        LABEL1 = Label.label( "LABEL1-" + testName.getMethodName() );
        LABEL2 = Label.label( "LABEL2-" + testName.getMethodName() );
        LABEL3 = Label.label( "LABEL3-" + testName.getMethodName() );
    }

    /* This test is a bit interesting. It tests a case where we've got a property that sits in one
     * property block and the value is of a long type. So given that plus that there's an index for that
     * label/property, do an update that changes the long value into a value that requires two property blocks.
     * This is interesting because the transaction logic compares before/after views per property record and
     * not per node as a whole.
     *
     * In this case this change will be converted into one "add" and one "remove" property updates instead of
     * a single "change" property update. At the very basic level it's nice to test for this corner-case so
     * that the externally observed behavior is correct, even if this test doesn't assert anything about
     * the underlying add/remove vs. change internal details.
     */
    @Test
    public void shouldInterpretPropertyAsChangedEvenIfPropertyMovesFromOneRecordToAnother()
    {
        // GIVEN
        GraphDatabaseService beansAPI = dbRule.getGraphDatabaseAPI();
        long smallValue = 10L;
        long bigValue = 1L << 62;
        Node myNode;
        try ( Transaction tx = beansAPI.beginTx() )
        {
            myNode = tx.createNode( LABEL1 );
            myNode.setProperty( "pad0", true );
            myNode.setProperty( "pad1", true );
            myNode.setProperty( "pad2", true );
            // Use a small long here which will only occupy one property block
            myNode.setProperty( "key", smallValue );

            tx.commit();
        }

        Neo4jMatchers.createIndex( beansAPI, LABEL1, "key" );

        // WHEN
        try ( Transaction tx = beansAPI.beginTx() )
        {
            // A big long value which will occupy two property blocks
            myNode.setProperty( "key", bigValue );
            tx.commit();
        }

        try ( Transaction transaction = beansAPI.beginTx() )
        {
            // THEN
            assertThat( findNodesByLabelAndProperty( LABEL1, "key", bigValue, beansAPI ), containsOnly( myNode ) );
            assertThat( findNodesByLabelAndProperty( LABEL1, "key", smallValue, beansAPI ), isEmpty() );
        }
    }

    @Test
    public void shouldUseDynamicPropertiesToIndexANodeWhenAddedAlongsideExistingPropertiesInASeparateTransaction()
    {
        // Given
        GraphDatabaseService beansAPI = dbRule.getGraphDatabaseAPI();

        // When
        long id;
        try ( Transaction tx = beansAPI.beginTx() )
        {
            Node myNode = tx.createNode();
            id = myNode.getId();
            myNode.setProperty( "key0", true );
            myNode.setProperty( "key1", true );

            tx.commit();
        }

        Neo4jMatchers.createIndex( beansAPI, LABEL1, "key2" );
        Node myNode;
        try ( Transaction tx = beansAPI.beginTx() )
        {
            myNode = beansAPI.getNodeById( id );
            myNode.addLabel( LABEL1 );
            myNode.setProperty( "key2", LONG_STRING );
            myNode.setProperty( "key3", LONG_STRING );

            tx.commit();
        }

        try ( Transaction transaction = beansAPI.beginTx() )
        {
            // Then
            assertThat( myNode, hasProperty( "key2" ).withValue( LONG_STRING ) );
            assertThat( myNode, hasProperty( "key3" ).withValue( LONG_STRING ) );
            assertThat( findNodesByLabelAndProperty( LABEL1, "key2", LONG_STRING, beansAPI ), containsOnly( myNode ) );
        }
    }

    @Test
    public void searchingForNodeByPropertyShouldWorkWithoutIndex()
    {
        // Given
        GraphDatabaseService beansAPI = dbRule.getGraphDatabaseAPI();
        Node myNode = createNode( beansAPI, map( "name", "Hawking" ), LABEL1 );

        // When
        try ( Transaction transaction = beansAPI.beginTx() )
        {
            assertThat( findNodesByLabelAndProperty( LABEL1, "name", "Hawking", beansAPI ), containsOnly( myNode ) );
        }
    }

    @Test
    public void searchingUsesIndexWhenItExists()
    {
        // Given
        GraphDatabaseService beansAPI = dbRule.getGraphDatabaseAPI();
        Node myNode = createNode( beansAPI, map( "name", "Hawking" ), LABEL1 );
        Neo4jMatchers.createIndex( beansAPI, LABEL1, "name" );

        // When
        try ( Transaction transaction = beansAPI.beginTx() )
        {
            assertThat( findNodesByLabelAndProperty( LABEL1, "name", "Hawking", beansAPI ), containsOnly( myNode ) );
        }
    }

    @Test
    public void shouldCorrectlyUpdateIndexesWhenChangingLabelsAndPropertyAtTheSameTime()
    {
        // Given
        GraphDatabaseService beansAPI = dbRule.getGraphDatabaseAPI();
        Node myNode = createNode( beansAPI, map( "name", "Hawking" ), LABEL1, LABEL2 );
        Neo4jMatchers.createIndex( beansAPI, LABEL1, "name" );
        Neo4jMatchers.createIndex( beansAPI, LABEL2, "name" );
        Neo4jMatchers.createIndex( beansAPI, LABEL3, "name" );

        // When
        try ( Transaction tx = beansAPI.beginTx() )
        {
            myNode.removeLabel( LABEL1 );
            myNode.addLabel( LABEL3 );
            myNode.setProperty( "name", "Einstein" );
            tx.commit();
        }

        try ( Transaction transaction = beansAPI.beginTx() )
        {
            // Then
            assertThat( myNode, hasProperty( "name" ).withValue( "Einstein" ) );
            assertThat( labels( myNode ), containsOnly( LABEL2, LABEL3 ) );

            assertThat( findNodesByLabelAndProperty( LABEL1, "name", "Hawking", beansAPI ), isEmpty() );
            assertThat( findNodesByLabelAndProperty( LABEL1, "name", "Einstein", beansAPI ), isEmpty() );

            assertThat( findNodesByLabelAndProperty( LABEL2, "name", "Hawking", beansAPI ), isEmpty() );
            assertThat( findNodesByLabelAndProperty( LABEL2, "name", "Einstein", beansAPI ), containsOnly( myNode ) );

            assertThat( findNodesByLabelAndProperty( LABEL3, "name", "Hawking", beansAPI ), isEmpty() );
            assertThat( findNodesByLabelAndProperty( LABEL3, "name", "Einstein", beansAPI ), containsOnly( myNode ) );
            transaction.commit();
        }
    }

    @Test
    public void shouldCorrectlyUpdateIndexesWhenChangingLabelsAndPropertyMultipleTimesAllAtOnce()
    {
        // Given
        GraphDatabaseService beansAPI = dbRule.getGraphDatabaseAPI();
        Node myNode = createNode( beansAPI, map( "name", "Hawking" ), LABEL1, LABEL2 );
        Neo4jMatchers.createIndex( beansAPI, LABEL1, "name" );
        Neo4jMatchers.createIndex( beansAPI, LABEL2, "name" );
        Neo4jMatchers.createIndex( beansAPI, LABEL3, "name" );

        // When
        try ( Transaction tx = beansAPI.beginTx() )
        {
            myNode.addLabel( LABEL3 );
            myNode.setProperty( "name", "Einstein" );
            myNode.removeLabel( LABEL1 );
            myNode.setProperty( "name", "Feynman" );
            tx.commit();
        }

        try ( Transaction transaction = beansAPI.beginTx() )
        {
            // Then
            assertThat( myNode, hasProperty( "name" ).withValue( "Feynman" ) );
            assertThat( labels( myNode ), containsOnly( LABEL2, LABEL3 ) );

            assertThat( findNodesByLabelAndProperty( LABEL1, "name", "Hawking", beansAPI ), isEmpty() );
            assertThat( findNodesByLabelAndProperty( LABEL1, "name", "Einstein", beansAPI ), isEmpty() );
            assertThat( findNodesByLabelAndProperty( LABEL1, "name", "Feynman", beansAPI ), isEmpty() );

            assertThat( findNodesByLabelAndProperty( LABEL2, "name", "Hawking", beansAPI ), isEmpty() );
            assertThat( findNodesByLabelAndProperty( LABEL2, "name", "Einstein", beansAPI ), isEmpty() );
            assertThat( findNodesByLabelAndProperty( LABEL2, "name", "Feynman", beansAPI ), containsOnly( myNode ) );

            assertThat( findNodesByLabelAndProperty( LABEL3, "name", "Hawking", beansAPI ), isEmpty() );
            assertThat( findNodesByLabelAndProperty( LABEL3, "name", "Einstein", beansAPI ), isEmpty() );
            assertThat( findNodesByLabelAndProperty( LABEL3, "name", "Feynman", beansAPI ), containsOnly( myNode ) );
            transaction.commit();
        }
    }

    @Test
    public void searchingByLabelAndPropertyReturnsEmptyWhenMissingLabelOrProperty()
    {
        // Given
        GraphDatabaseService beansAPI = dbRule.getGraphDatabaseAPI();

        // When/Then
        try ( Transaction transaction = beansAPI.beginTx() )
        {
            assertThat( findNodesByLabelAndProperty( LABEL1, "name", "Hawking", beansAPI ), isEmpty() );
        }
    }

    @Test
    public void shouldSeeIndexUpdatesWhenQueryingOutsideTransaction()
    {
        // GIVEN
        GraphDatabaseService beansAPI = dbRule.getGraphDatabaseAPI();
        Neo4jMatchers.createIndex( beansAPI, LABEL1, "name" );
        Node firstNode = createNode( beansAPI, map( "name", "Mattias" ), LABEL1 );

        // WHEN THEN
        try ( Transaction transaction = beansAPI.beginTx() )
        {
            assertThat( findNodesByLabelAndProperty( LABEL1, "name", "Mattias", beansAPI ), containsOnly( firstNode ) );
        }
        Node secondNode = createNode( beansAPI, map( "name", "Taylor" ), LABEL1 );
        try ( Transaction transaction = beansAPI.beginTx() )
        {
            assertThat( findNodesByLabelAndProperty( LABEL1, "name", "Taylor", beansAPI ), containsOnly( secondNode ) );
        }
    }

    @Test
    public void createdNodeShouldShowUpWithinTransaction()
    {
        // GIVEN
        GraphDatabaseService beansAPI = dbRule.getGraphDatabaseAPI();
        Neo4jMatchers.createIndex( beansAPI, LABEL1, "name" );

        // WHEN

        Node firstNode = createNode( beansAPI, map( "name", "Mattias" ), LABEL1 );
        long sizeBeforeDelete;
        long sizeAfterDelete;
        try ( Transaction tx = beansAPI.beginTx() )
        {
            sizeBeforeDelete = count( beansAPI.findNodes( LABEL1, "name", "Mattias" ) );
            firstNode.delete();
            sizeAfterDelete = count( beansAPI.findNodes( LABEL1, "name", "Mattias" ) );
            tx.commit();
        }

        // THEN
        assertThat( sizeBeforeDelete, equalTo(1L) );
        assertThat( sizeAfterDelete, equalTo(0L) );
    }

    @Test
    public void deletedNodeShouldShowUpWithinTransaction()
    {
        // GIVEN
        GraphDatabaseService beansAPI = dbRule.getGraphDatabaseAPI();
        Neo4jMatchers.createIndex( beansAPI, LABEL1, "name" );
        Node firstNode = createNode( beansAPI, map( "name", "Mattias" ), LABEL1 );

        // WHEN
        long sizeBeforeDelete;
        long sizeAfterDelete;
        try ( Transaction tx = beansAPI.beginTx() )
        {
            sizeBeforeDelete = count( beansAPI.findNodes( LABEL1, "name", "Mattias" ) );
            firstNode.delete();
            sizeAfterDelete = count( beansAPI.findNodes( LABEL1, "name", "Mattias" ) );
            tx.commit();
        }

        // THEN
        assertThat( sizeBeforeDelete, equalTo(1L) );
        assertThat( sizeAfterDelete, equalTo(0L) );
    }

    @Test
    public void createdNodeShouldShowUpInIndexQuery()
    {
        // GIVEN
        GraphDatabaseService beansAPI = dbRule.getGraphDatabaseAPI();
        Neo4jMatchers.createIndex( beansAPI, LABEL1, "name" );
        createNode( beansAPI, map( "name", "Mattias" ), LABEL1 );

        // WHEN
        long sizeBeforeDelete;
        long sizeAfterDelete;
        try ( Transaction transaction = beansAPI.beginTx() )
        {
            sizeBeforeDelete = count( beansAPI.findNodes( LABEL1, "name", "Mattias" ) );
        }
        createNode( beansAPI, map( "name", "Mattias" ), LABEL1 );
        try ( Transaction transaction = beansAPI.beginTx() )
        {
            sizeAfterDelete = count( beansAPI.findNodes( LABEL1, "name", "Mattias" ) );
        }

        // THEN
        assertThat( sizeBeforeDelete, equalTo(1L) );
        assertThat( sizeAfterDelete, equalTo(2L) );
    }

    @Test
    public void shouldBeAbleToQuerySupportedPropertyTypes()
    {
        // GIVEN
        String property = "name";
        GraphDatabaseService db = dbRule.getGraphDatabaseAPI();
        Neo4jMatchers.createIndex( db, LABEL1, property );

        // WHEN & THEN
        assertCanCreateAndFind( db, LABEL1, property, "A String" );
        assertCanCreateAndFind( db, LABEL1, property, true );
        assertCanCreateAndFind( db, LABEL1, property, false );
        assertCanCreateAndFind( db, LABEL1, property, (byte) 56 );
        assertCanCreateAndFind( db, LABEL1, property, 'z' );
        assertCanCreateAndFind( db, LABEL1, property, (short)12 );
        assertCanCreateAndFind( db, LABEL1, property, 12 );
        assertCanCreateAndFind( db, LABEL1, property, 12L );
        assertCanCreateAndFind( db, LABEL1, property, (float)12. );
        assertCanCreateAndFind( db, LABEL1, property, 12. );
        assertCanCreateAndFind( db, LABEL1, property, SpatialMocks.mockPoint( 12.3, 45.6, mockWGS84() ) );
        assertCanCreateAndFind( db, LABEL1, property, SpatialMocks.mockPoint( 123, 456, mockCartesian() ) );
        assertCanCreateAndFind( db, LABEL1, property, SpatialMocks.mockPoint( 12.3, 45.6, 100.0, mockWGS84_3D() ) );
        assertCanCreateAndFind( db, LABEL1, property, SpatialMocks.mockPoint( 123, 456, 789, mockCartesian_3D() ) );
        assertCanCreateAndFind( db, LABEL1, property, Values.pointValue( CoordinateReferenceSystem.WGS84, 12.3, 45.6 ) );
        assertCanCreateAndFind( db, LABEL1, property, Values.pointValue( CoordinateReferenceSystem.Cartesian, 123, 456 ) );
        assertCanCreateAndFind( db, LABEL1, property, Values.pointValue( CoordinateReferenceSystem.WGS84_3D, 12.3, 45.6, 100.0 ) );
        assertCanCreateAndFind( db, LABEL1, property, Values.pointValue( CoordinateReferenceSystem.Cartesian_3D, 123, 456, 789 ) );

        assertCanCreateAndFind( db, LABEL1, property, new String[]{"A String"} );
        assertCanCreateAndFind( db, LABEL1, property, new boolean[]{true} );
        assertCanCreateAndFind( db, LABEL1, property, new Boolean[]{false} );
        assertCanCreateAndFind( db, LABEL1, property, new byte[]{56} );
        assertCanCreateAndFind( db, LABEL1, property, new Byte[]{57} );
        assertCanCreateAndFind( db, LABEL1, property, new char[]{'a'} );
        assertCanCreateAndFind( db, LABEL1, property, new Character[]{'b'} );
        assertCanCreateAndFind( db, LABEL1, property, new short[]{12} );
        assertCanCreateAndFind( db, LABEL1, property, new Short[]{13} );
        assertCanCreateAndFind( db, LABEL1, property, new int[]{14} );
        assertCanCreateAndFind( db, LABEL1, property, new Integer[]{15} );
        assertCanCreateAndFind( db, LABEL1, property, new long[]{16L} );
        assertCanCreateAndFind( db, LABEL1, property, new Long[]{17L} );
        assertCanCreateAndFind( db, LABEL1, property, new float[]{(float)18.} );
        assertCanCreateAndFind( db, LABEL1, property, new Float[]{(float)19.} );
        assertCanCreateAndFind( db, LABEL1, property, new double[]{20.} );
        assertCanCreateAndFind( db, LABEL1, property, new Double[]{21.} );
        assertCanCreateAndFind( db, LABEL1, property, new Point[]{SpatialMocks.mockPoint( 12.3, 45.6, mockWGS84() )} );
        assertCanCreateAndFind( db, LABEL1, property, new Point[]{SpatialMocks.mockPoint( 123, 456, mockCartesian() )} );
        assertCanCreateAndFind( db, LABEL1, property, new Point[]{SpatialMocks.mockPoint( 12.3, 45.6, 100.0, mockWGS84_3D() )} );
        assertCanCreateAndFind( db, LABEL1, property, new Point[]{SpatialMocks.mockPoint( 123, 456, 789, mockCartesian_3D() )} );
        assertCanCreateAndFind( db, LABEL1, property, new PointValue[]{Values.pointValue( CoordinateReferenceSystem.WGS84, 12.3, 45.6 )} );
        assertCanCreateAndFind( db, LABEL1, property, new PointValue[]{Values.pointValue( CoordinateReferenceSystem.Cartesian, 123, 456 )} );
        assertCanCreateAndFind( db, LABEL1, property, new PointValue[]{Values.pointValue( CoordinateReferenceSystem.WGS84_3D, 12.3, 45.6, 100.0 )} );
        assertCanCreateAndFind( db, LABEL1, property, new PointValue[]{Values.pointValue( CoordinateReferenceSystem.Cartesian_3D, 123, 456, 789 )} );
    }

    @Test
    public void shouldRetrieveMultipleNodesWithSameValueFromIndex()
    {
        // this test was included here for now as a precondition for the following test

        // given
        GraphDatabaseService graph = dbRule.getGraphDatabaseAPI();
        Neo4jMatchers.createIndex( graph, LABEL1, "name" );

        Node node1;
        Node node2;
        try ( Transaction tx = graph.beginTx() )
        {
            node1 = tx.createNode( LABEL1 );
            node1.setProperty( "name", "Stefan" );

            node2 = tx.createNode( LABEL1 );
            node2.setProperty( "name", "Stefan" );
            tx.commit();
        }

        try ( Transaction tx = graph.beginTx() )
        {
            ResourceIterator<Node> result = graph.findNodes( LABEL1, "name", "Stefan" );
            assertEquals( asSet( node1, node2 ), asSet( result ) );

            tx.commit();
        }
    }

    @Test
    public void shouldThrowWhenMultipleResultsForSingleNode()
    {
        // given
        GraphDatabaseService graph = dbRule.getGraphDatabaseAPI();
        Neo4jMatchers.createIndex( graph, LABEL1, "name" );

        Node node1;
        Node node2;
        try ( Transaction tx = graph.beginTx() )
        {
            node1 = tx.createNode( LABEL1 );
            node1.setProperty( "name", "Stefan" );

            node2 = tx.createNode( LABEL1 );
            node2.setProperty( "name", "Stefan" );
            tx.commit();
        }

        try ( Transaction tx = graph.beginTx() )
        {
            graph.findNode( LABEL1, "name", "Stefan" );
            fail( "Expected MultipleFoundException but got none" );
        }
        catch ( MultipleFoundException e )
        {
            assertThat( e.getMessage(), equalTo(
                    format( "Found multiple nodes with label: '%s', property name: 'name' " +
                            "and property value: 'Stefan' while only one was expected.", LABEL1 ) ) );
        }
    }

    @Test
    public void shouldAddIndexedPropertyToNodeWithDynamicLabels()
    {
        // Given
        int indexesCount = 20;
        String labelPrefix = "foo";
        String propertyKeyPrefix = "bar";
        String propertyValuePrefix = "baz";
        GraphDatabaseService db = dbRule.getGraphDatabaseAPI();

        for ( int i = 0; i < indexesCount; i++ )
        {
            Neo4jMatchers.createIndexNoWait( db, Label.label( labelPrefix + i ), propertyKeyPrefix + i );
        }
        Neo4jMatchers.waitForIndexes( db );

        // When
        long nodeId;
        try ( Transaction tx = db.beginTx() )
        {
            nodeId = tx.createNode().getId();
            tx.commit();
        }

        try ( Transaction tx = db.beginTx() )
        {
            Node node = db.getNodeById( nodeId );
            for ( int i = 0; i < indexesCount; i++ )
            {
                node.addLabel( Label.label( labelPrefix + i ) );
                node.setProperty( propertyKeyPrefix + i, propertyValuePrefix + i );
            }
            tx.commit();
        }

        // Then
        try ( Transaction tx = db.beginTx() )
        {
            for ( int i = 0; i < indexesCount; i++ )
            {
                Label label = Label.label( labelPrefix + i );
                String key = propertyKeyPrefix + i;
                String value = propertyValuePrefix + i;

                ResourceIterator<Node> nodes = db.findNodes( label, key, value );
                assertEquals( 1, Iterators.count( nodes ) );
            }
            tx.commit();
        }
    }

    private void assertCanCreateAndFind( GraphDatabaseService db, Label label, String propertyKey, Object value )
    {
        Node created = createNode( db, map( propertyKey, value ), label );

        try ( Transaction tx = db.beginTx() )
        {
            Node found = db.findNode( label, propertyKey, value );
            assertThat( found, equalTo( created ) );
            found.delete();
            tx.commit();
        }
    }

    private Node createNode( GraphDatabaseService beansAPI, Map<String, Object> properties, Label... labels )
    {
        try ( Transaction tx = beansAPI.beginTx() )
        {
            Node node = tx.createNode( labels );
            for ( Map.Entry<String,Object> property : properties.entrySet() )
            {
                node.setProperty( property.getKey(), property.getValue() );
            }
            tx.commit();
            return node;
        }
    }

    private Neo4jMatchers.Deferred<Label> labels( final Node myNode )
    {
        return new Neo4jMatchers.Deferred<Label>( dbRule.getGraphDatabaseAPI() )
        {
            @Override
            protected Iterable<Label> manifest()
            {
                return myNode.getLabels();
            }
        };
    }
}

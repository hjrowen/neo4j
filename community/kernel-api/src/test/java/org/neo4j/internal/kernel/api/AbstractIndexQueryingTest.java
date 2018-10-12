/*
 * Copyright (c) 2002-2018 "Neo4j,"
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
package org.neo4j.internal.kernel.api;

import org.junit.Test;

import java.util.concurrent.TimeUnit;

import org.neo4j.graphdb.GraphDatabaseService;
import org.neo4j.graphdb.Transaction;
import org.neo4j.internal.kernel.api.exceptions.schema.IndexNotApplicableKernelException;

public abstract class AbstractIndexQueryingTest<S extends KernelAPIReadTestSupport> extends KernelAPIReadTestBase<S>
{
    @Override
    public void createTestGraph( GraphDatabaseService db )
    {
        try ( Transaction tx = db.beginTx() )
        {
            db.execute( "call db.index.fulltext.createNodeIndex('ftsNodes', ['Label'], ['prop'])" ).close();
            db.execute( "call db.index.fulltext.createRelationshipIndex('ftsRels', ['Type'], ['prop'])" ).close();
            tx.success();
        }
        try ( Transaction tx = db.beginTx() )
        {
            db.schema().awaitIndexesOnline( 1, TimeUnit.MINUTES );
            tx.success();
        }
    }

    @Test( expected = IndexNotApplicableKernelException.class )
    public void nodeIndexSeekMustThrowOnWrongIndexEntityType() throws Exception
    {
        IndexReference index = schemaRead.indexGetForName( "ftsRels" );
        try ( NodeValueIndexCursor cursor = cursors.allocateNodeValueIndexCursor() )
        {
            read.nodeIndexSeek( index, cursor, IndexOrder.NONE, false, IndexQuery.fulltextSearch( "search" ) );
        }
    }

    @Test( expected = IndexNotApplicableKernelException.class )
    public void relationshipIndexSeekMustThrowOnWrongIndexEntityType() throws Exception
    {
        IndexReference index = schemaRead.indexGetForName( "ftsNodes" );
        try ( RelationshipIndexCursor cursor = cursors.allocateRelationshipIndexCursor() )
        {
            read.relationshipIndexSeek( index, cursor, IndexQuery.fulltextSearch( "search" ) );
        }
    }
}
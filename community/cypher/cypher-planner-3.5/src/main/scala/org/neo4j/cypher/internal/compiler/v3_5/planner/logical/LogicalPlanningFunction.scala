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
package org.neo4j.cypher.internal.compiler.v3_5.planner.logical

import org.neo4j.cypher.internal.ir.v3_5.{PlannerQuery, QueryGraph, RequiredOrder}
import org.neo4j.cypher.internal.v3_5.logical.plans.LogicalPlan
import org.opencypher.v9_0.expressions.Expression

// TODO: Return Iterator
trait CandidateGenerator[T] extends {
  def apply(in: T, queryGraph: QueryGraph, requiredOrder: RequiredOrder, context: LogicalPlanningContext): Seq[T]
}

trait PlanSelector {
  def apply(input: LogicalPlan, queryGraph: QueryGraph, requiredOrder: RequiredOrder, context: LogicalPlanningContext): LogicalPlan
}

trait PlanTransformer {
  def apply(plan: LogicalPlan, expected: PlannerQuery, context: LogicalPlanningContext): LogicalPlan
}

trait PlanAndContextTransformer {
  def apply(plan: LogicalPlan, query: PlannerQuery, requiredOrder: RequiredOrder, context: LogicalPlanningContext): (LogicalPlan, LogicalPlanningContext)
}

trait CandidateSelector extends ProjectingSelector[LogicalPlan]

trait LeafPlanner {
  def apply(queryGraph: QueryGraph, requiredOrder: RequiredOrder, context: LogicalPlanningContext): Seq[LogicalPlan]
}

object LeafPlansForVariable {
  def maybeLeafPlans(id: String, plans: Set[LogicalPlan]): Option[LeafPlansForVariable] =
    if (plans.isEmpty) None else Some(LeafPlansForVariable(id, plans))
}

case class LeafPlansForVariable(id: String, plans: Set[LogicalPlan]) {
  assert(plans.nonEmpty)
}

trait LeafPlanFromExpressions {
  def producePlanFor(predicates: Set[Expression], qg: QueryGraph, requiredOrder: RequiredOrder, context: LogicalPlanningContext): Set[LeafPlansForVariable]
}

trait LeafPlanFromExpression extends LeafPlanFromExpressions {

  def producePlanFor(e: Expression, qg: QueryGraph, requiredOrder: RequiredOrder, context: LogicalPlanningContext): Option[LeafPlansForVariable]


  override def producePlanFor(predicates: Set[Expression], qg: QueryGraph, requiredOrder: RequiredOrder, context: LogicalPlanningContext): Set[LeafPlansForVariable] = {
    predicates.flatMap(p => producePlanFor(p, qg, requiredOrder, context))
  }
}

trait LeafPlanFinder {
  def apply(config: QueryPlannerConfiguration, queryGraph: QueryGraph, requiredOrder: RequiredOrder, context: LogicalPlanningContext): Set[LogicalPlan]
}
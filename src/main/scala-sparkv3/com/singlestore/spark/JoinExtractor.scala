package com.singlestore.spark

import org.apache.spark.sql.catalyst.expressions.Expression
import org.apache.spark.sql.catalyst.plans.JoinType
import org.apache.spark.sql.catalyst.plans.logical.{Join, LogicalPlan}

case object JoinExtractor {
  def unapply(l: LogicalPlan): Option[(LogicalPlan, LogicalPlan, JoinType, Option[Expression])] = {
    l match {
      // the last parameter is a spark hint for join
      // SingleStore does its own optimizations under the hood, so we can safely ignore this parameter
      case Join(left, right, joinType, condition, _) => Some((left, right, joinType, condition))
      case _                                         => None
    }
  }
}
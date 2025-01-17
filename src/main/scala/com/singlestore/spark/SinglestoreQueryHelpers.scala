package com.singlestore.spark

import com.singlestore.spark.SQLGen.VariableList
import org.apache.spark.Partition
import org.apache.spark.sql.execution.datasources.jdbc.JDBCOptions
import spray.json.DefaultJsonProtocol._
import spray.json._

case class SinglestorePartition(
    override val index: Int,
    query: String,
    variables: VariableList,
    connectionInfo: JDBCOptions
) extends Partition

object SinglestoreQueryHelpers extends LazyLogging {
  val executorWhitelist = Set(
    "Project",
    "Gather",
    "Filter",
    "TableScan",
    "ColumnStoreScan",
    "ColumnStoreFilter",
    "OrderedColumnStoreScan",
    "IndexRangeScan",
    "IndexSeek",
    "NestedLoopJoin",
    "ChoosePlan",
    "HashGroupBy",
    "StreamingGroupBy"
  ).map(_.toLowerCase)

  def GetPartitions(options: SinglestoreOptions,
                    query: String,
                    variables: VariableList): Array[Partition] = {

    // currently we require the database name to be provided in order to do partition pushdown
    // this is because we need to replace the database name in the generated query from SingleStore explain
    val partitions = if (options.enableParallelRead && options.database.isDefined) {
      val explainJSON        = JdbcHelpers.explainJSONQuery(options, query, variables).parseJson
      val partitionHostPorts = JdbcHelpers.partitionHostPorts(options, options.database.head)
      try {
        partitionsFromExplainJSON(options, options.database.head, partitionHostPorts, explainJSON)
      } catch {
        case _: DeserializationException => None
      }
    } else { None }

    partitions
      .getOrElse(
        Array(SinglestorePartition(0, query, variables, JdbcHelpers.getDMLJDBCOptions(options))))
  }

  def partitionsFromExplainJSON(options: SinglestoreOptions,
                                database: String,
                                partitionHostPorts: List[SinglestorePartitionInfo],
                                explainJSON: JsValue): Option[Array[Partition]] = {
    // The top level Explain is either the explain tree or a "metadata" wrapper
    // (we can disambiguate between these cases by checking for the presence of the "version" key)

    var root       = explainJSON
    val rootFields = explainJSON.asJsObject().fields
    if (rootFields.contains("version")) {
      // In explain version 2+ (introduced in SingleStore 6.7), the output
      // we want is nested under the "explain" key as the only element of an array
      root = rootFields("explain").convertTo[List[JsObject]].head
    }

    // Collect required execution metadata
    // We need to walk through the tree and collect the "executor" and "query" fields from each node

    def walk(node: JsValue): Seq[(Option[String], Option[String])] = {
      val fields   = node.asJsObject.fields
      val executor = fields.get("executor").map(_.convertTo[String].toLowerCase)
      val query    = fields.get("query").map(_.convertTo[String])
      val children =
        fields.get("inputs").map(_.convertTo[Seq[JsValue]].flatMap(walk)).getOrElse(Nil)
      Seq((executor, query)) ++ children
    }

    val (maybeExecutors, maybeQueries) = walk(root).unzip
    val executors                      = maybeExecutors.flatten
    val queries                        = maybeQueries.flatten

    // We are able to pushdown when the following conditions hold:
    // 1. there is exactly one query
    if (queries.length != 1) {
      if (log.isTraceEnabled) {
        log.trace(
          s"Parallel read disabled for this query: SingleStore would run more than one query during execution (${queries.length} queries found)")
      }
      return None
    }

    // 2. all of the executors are in our whitelist
    if (executors.map(!executorWhitelist.contains(_)).exists(identity)) {
      if (log.isTraceEnabled) {
        log.trace(
          s"Parallel read disabled for this query: SingleStore is using parallel-unsafe executors (distinct executors in use: ${executors.toSet
            .mkString(", ")})")
      }
      return None
    }

    // 3. there is only one gather, and it is the first executor
    val numGathers  = executors.count(_ == "gather")
    val gatherFirst = executors.headOption.contains("gather")
    if (numGathers != 1 || !gatherFirst) {
      if (log.isTraceEnabled) {
        log.trace(
          s"Parallel read disabled for this query: the gather method used by this query is not supported (${numGathers}, ${gatherFirst})")
      }
      return None
    }

    // Success! we can do partition pushdown, to do this we need to generate queries for each partition

    // we checked earlier that queries.length == 1, so we can safely grab the first query here
    var partitionQuery = queries.head
    // the partitionQuery may start with USING, so lets remove everything up to the first SELECT
    partitionQuery = partitionQuery.slice(partitionQuery.indexOf("SELECT"), partitionQuery.length)

    val firstPartitionName = s"${database}_0"

    Some(
      partitionHostPorts
        .map(p =>
          SinglestorePartition(
            p.ordinal,
            partitionQuery.replace(firstPartitionName, p.name),
            // SingleStore has already injected our variables into the query
            // so we don't have to do any additional injection
            Nil,
            JdbcHelpers.getJDBCOptions(options, p.hostport)
        ))
        .toArray)
  }
}

// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.

package org.apache.impala.planner;

import java.util.ArrayList;
import java.util.List;

import org.apache.impala.analysis.Analyzer;
import org.apache.impala.analysis.Expr;
import org.apache.impala.analysis.ExprSubstitutionMap;
import org.apache.impala.analysis.FunctionCallExpr;
import org.apache.impala.analysis.FunctionName;
import org.apache.impala.analysis.FunctionParams;
import org.apache.impala.analysis.MultiAggregateInfo;
import org.apache.impala.analysis.SlotDescriptor;
import org.apache.impala.analysis.SlotRef;
import org.apache.impala.analysis.TupleDescriptor;
import org.apache.impala.catalog.HdfsFileFormat;
import org.apache.impala.catalog.Type;
import org.apache.impala.common.NotImplementedException;
import org.apache.impala.common.PrintUtils;
import org.apache.impala.common.RuntimeEnv;
import org.apache.impala.service.BackendConfig;
import org.apache.impala.thrift.TNetworkAddress;
import org.apache.impala.thrift.TQueryOptions;
import org.apache.impala.thrift.TScanRangeSpec;
import org.apache.impala.thrift.TTableStats;
import org.apache.impala.util.ExprUtil;

import com.google.common.base.Joiner;
import com.google.common.base.MoreObjects;
import com.google.common.base.Preconditions;
import com.google.common.math.IntMath;
import com.google.common.math.LongMath;

/**
 * Representation of the common elements of all scan nodes.
 */
abstract public class ScanNode extends PlanNode {
  // Factor capturing the worst-case deviation from a uniform distribution of scan ranges
  // among nodes. The factor of 1.2 means that a particular node may have 20% more
  // scan ranges than would have been estimated assuming a uniform distribution.
  // Used for HDFS and Kudu Scan node estimations.
  protected static final double SCAN_RANGE_SKEW_FACTOR = 1.2;

  protected final TupleDescriptor desc_;

  // Total number of rows this node is expected to process
  protected long inputCardinality_ = -1;

  // Scan-range specs. Populated in init().
  protected TScanRangeSpec scanRangeSpecs_;

  // The AggregationInfo from the query block of this scan node. Used for determining if
  // the count(*) optimization can be applied.
  // Count(*) aggregation optimization flow:
  // The caller passes in a MultiAggregateInfo to the constructor that this scan node
  // uses to determine whether to apply the optimization or not. The produced smap must
  // then be applied to the MultiAggregateInfo in this query block. We do not apply the
  // smap in this class directly to avoid side effects and make it easier to reason about.
  protected MultiAggregateInfo aggInfo_ = null;
  protected static final String STATS_NUM_ROWS = "stats: num_rows";

  // Should be applied to the AggregateInfo from the same query block. We cannot use the
  // PlanNode.outputSmap_ for this purpose because we don't want the smap entries to be
  // propagated outside the query block.
  protected ExprSubstitutionMap optimizedAggSmap_;

  // Refer to the comment of 'TableRef.tableNumRowsHint_'
  protected long tableNumRowsHint_ = -1;

  public ScanNode(PlanNodeId id, TupleDescriptor desc, String displayName) {
    super(id, desc.getId().asList(), displayName);
    desc_ = desc;
  }

  @Override
  public void computeStats(Analyzer analyzer) {
    super.computeStats(analyzer);
    hasHardEstimates_ = !hasScanConjuncts() && !isAccessingCollectionType();
  }

  public TupleDescriptor getTupleDesc() { return desc_; }

  @Override
  protected boolean shouldPickUpZippingUnnestConjuncts() { return true; }

  /**
   * Checks if this scan is supported based on the types of scanned columns and the
   * underlying file formats, in particular, whether complex types are supported.
   *
   * The default implementation throws if this scan would need to materialize a nested
   * field or collection. The scan is ok if the table schema contains complex types, as
   * long as the query does not reference them.
   *
   * Subclasses should override this function as appropriate.
   */
  protected void checkForSupportedFileFormats() throws NotImplementedException {
    Preconditions.checkNotNull(desc_);
    Preconditions.checkNotNull(desc_.getTable());
    for (SlotDescriptor slotDesc: desc_.getSlots()) {
      if (slotDesc.getType().isComplexType() || slotDesc.getColumn() == null) {
        Preconditions.checkNotNull(slotDesc.getPath());
        throw new NotImplementedException(String.format(
            "Scan of table '%s' is not supported because '%s' references a nested " +
            "field/collection.\nComplex types are supported for these file formats: %s.",
            slotDesc.getPath().toString(), desc_.getAlias(),
            Joiner.on(", ").join(HdfsFileFormat.complexTypesFormats())));
      }
    }
  }

  protected boolean isCountStarOptimizationDescriptor(SlotDescriptor desc) {
    return desc.getLabel().equals(STATS_NUM_ROWS);
  }

  protected SlotDescriptor applyCountStarOptimization(Analyzer analyzer) {
    return applyCountStarOptimization(analyzer, null);
  }

  /**
   * Adds a new slot descriptor to the tuple descriptor of this scan. The new slot
   * will be used for storing the data extracted from the Parquet or Kudu num rows
   * statistic. If the caller has supplied a countFnExpr (such as from an external
   * frontend), use that. Otherwise, create a count(*) function expr. Also adds an
   * entry to 'optimizedAggSmap_' that substitutes count(*) with
   * sum_init_zero(<new-slotref>). Returns the new slot descriptor.
   */
  protected SlotDescriptor applyCountStarOptimization(Analyzer analyzer,
      FunctionCallExpr countFnExpr) {
    FunctionCallExpr countFn = countFnExpr != null ? countFnExpr :
        new FunctionCallExpr(new FunctionName("count"),
            FunctionParams.createStarParam());
    countFn.analyzeNoThrow(analyzer);

    // Create the sum function.
    SlotDescriptor sd = analyzer.addSlotDescriptor(getTupleDesc());
    sd.setType(Type.BIGINT);
    sd.setIsMaterialized(true);
    sd.setIsNullable(false);
    sd.setLabel(STATS_NUM_ROWS);
    List<Expr> args = new ArrayList<>();
    args.add(new SlotRef(sd));
    FunctionCallExpr sumFn = new FunctionCallExpr("sum_init_zero", args);
    sumFn.analyzeNoThrow(analyzer);

    optimizedAggSmap_ = new ExprSubstitutionMap();
    optimizedAggSmap_.put(countFn, sumFn);
    return sd;
  }

  /**
   * Returns true if the count(*) optimization can be applied to the query block
   * of this scan node.
   */
  protected boolean canApplyCountStarOptimization(Analyzer analyzer) {
    if (analyzer.getNumTableRefs() != 1)  return false;
    if (aggInfo_ == null || aggInfo_.getMaterializedAggClasses().size() != 1
        || !aggInfo_.getMaterializedAggClass(0).hasCountStarOnly()) {
      return false;
    }
    if (!conjuncts_.isEmpty()) return false;
    return !desc_.hasMaterializedSlots() || desc_.hasClusteringColsOnly();
  }

  /**
   * Returns all scan range specs.
   */
  public TScanRangeSpec getScanRangeSpecs() {
    Preconditions.checkNotNull(scanRangeSpecs_, "Need to call init() first.");
    return scanRangeSpecs_;
  }

  @Override
  protected String debugString() {
    return MoreObjects.toStringHelper(this)
        .add("tid", desc_.getId().asInt())
        .add("tblName", desc_.getTable().getFullName())
        .add("keyRanges", "")
        .addValue(super.debugString())
        .toString();
  }

  /**
   * Returns the explain string for table stats to be included into this ScanNode's
   * explain string. The prefix is prepended to each returned line for proper formatting
   * when the string returned by this method is embedded in a query's explain plan.
   */
  protected String getTableStatsExplainString(String prefix) {
    TTableStats tblStats = desc_.getTable().getTTableStats();
    return new StringBuilder()
      .append(prefix)
      .append("table: rows=")
      .append(PrintUtils.printEstCardinality(tblStats.num_rows))
      .toString();
  }

  /**
   * Returns the explain string for column stats to be included into this ScanNode's
   * explain string. The prefix is prepended to each returned line for proper formatting
   * when the string returned by this method is embedded in a query's explain plan.
   */
  protected String getColumnStatsExplainString(String prefix) {
    StringBuilder output = new StringBuilder();
    List<String> columnsMissingStats = new ArrayList<>();
    for (SlotDescriptor slot: desc_.getSlots()) {
      if (!slot.getStats().hasStats() && slot.getColumn() != null) {
        columnsMissingStats.add(slot.getColumn().getName());
      }
    }
    output.append(prefix);
    if (columnsMissingStats.isEmpty()) {
      output.append("columns: all");
    } else if (columnsMissingStats.size() == desc_.getSlots().size()) {
      output.append("columns: unavailable");
    } else {
      output.append(String.format("columns missing stats: %s",
          Joiner.on(", ").join(columnsMissingStats)));
    }
    return output.toString();
  }

  /**
   * Combines the explain string for table and column stats.
   */
  protected String getStatsExplainString(String prefix) {
    StringBuilder output = new StringBuilder(prefix);
    output.append("stored statistics:\n");
    prefix = prefix + "  ";
    output.append(getTableStatsExplainString(prefix));
    output.append("\n");
    output.append(getColumnStatsExplainString(prefix));
    return output.toString();
  }

  /**
   * Returns true if the table underlying this scan is missing table stats
   * or column stats relevant to this scan node.
   */
  public boolean isTableMissingStats() {
    return isTableMissingColumnStats() || isTableMissingTableStats();
  }

  public boolean isTableMissingTableStats() {
    return desc_.getTable().getNumRows() == -1;
  }

  /**
   * Returns true if the tuple descriptor references a path with a collection type.
   */
  public boolean isAccessingCollectionType() {
    for (Type t: desc_.getPath().getMatchedTypes()) {
      if (t.isCollectionType()) return true;
    }
    return false;
  }

  /**
   * Returns true if the column does not have stats, complex type columns are skipped.
   */
  public boolean isTableMissingColumnStats() {
    for (SlotDescriptor slot: desc_.getSlots()) {
      if (slot.getColumn() != null && !slot.getStats().hasStats() &&
          !slot.getColumn().getType().isComplexType()) {
        return true;
      }
    }
    return false;
  }

  /**
   * Returns true, if the scanned table is suspected to have corrupt table stats,
   * in particular, if the scan is non-empty and 'numRows' is 0 or negative (but not -1).
   */
  public boolean hasCorruptTableStats() { return false; }

  /**
   * Helper function to parse a "host:port" address string into TNetworkAddress
   * This is called with ipaddress:port when doing scan range assignment.
   */
  protected static TNetworkAddress addressToTNetworkAddress(String address) {
    TNetworkAddress result = new TNetworkAddress();
    String[] hostPort = address.split(":");
    result.hostname = hostPort[0];
    result.port = Integer.parseInt(hostPort[1]);
    return result;
  }

  @Override
  public long getInputCardinality() {
    if (!hasScanConjuncts() && !hasStorageLayerConjuncts() && hasLimit()) {
      return getLimit();
    }
    return inputCardinality_;
  }

  @Override
  protected String getDisplayLabelDetail() {
    Preconditions.checkNotNull(desc_.getPath());
    if (desc_.hasExplicitAlias()) {
      return desc_.getPath().toString() + " " + desc_.getAlias();
    } else {
      return desc_.getPath().toString();
    }
  }

  /**
   * Helper function that returns the estimated number of scan ranges that would
   * be assigned to each host based on the total number of scan ranges.
   */
  protected int estimatePerHostScanRanges(long totalNumOfScanRanges) {
    return (int) Math.ceil(((double) totalNumOfScanRanges / (double) numNodes_) *
        SCAN_RANGE_SKEW_FACTOR);
  }

  /**
   * Helper function that returns the max number of scanner threads that can be
   * spawned by a scan node.
   */
  protected int computeMaxNumberOfScannerThreads(TQueryOptions queryOptions,
      int perHostScanRanges) {
    // The non-MT scan node requires at least one scanner thread.
    if (Planner.useMTFragment(queryOptions)) {
      return 1;
    }
    int maxScannerThreads = Math.min(perHostScanRanges,
        RuntimeEnv.INSTANCE.getNumCores());
    // Account for the max scanner threads query option.
    if (queryOptions.isSetNum_scanner_threads() &&
        queryOptions.getNum_scanner_threads() > 0) {
      maxScannerThreads = Math.min(maxScannerThreads,
          queryOptions.getNum_scanner_threads());
    }
    return maxScannerThreads;
  }

  /**
   * Compute processing cost of this scan node.
   * <p>
   * This method does not mutate any state of the scan node object, including
   * the processingCost_ field. Caller must set processingCost_ themself with
   * the return value of this method.
   */
  protected ProcessingCost computeScanProcessingCost(TQueryOptions queryOptions) {
    Preconditions.checkArgument(queryOptions.isCompute_processing_cost());
    ProcessingCost cardinalityBasedCost =
        ProcessingCost.basicCost(getDisplayLabel(), getInputCardinality(),
            ExprUtil.computeExprsTotalCost(conjuncts_), rowMaterializationCost());

    // maxThread calculation below intentionally does not include core count from
    // executor group config. This is to allow scan fragment parallelism to scale
    // regardless of the core count limit.
    int maxThreadsPerNode = Math.max(queryOptions.getProcessing_cost_min_threads(),
        queryOptions.getMax_fragment_instances_per_node());
    int maxScannerThreads = (int) Math.min(getEffectiveNumScanRanges(),
        IntMath.saturatedMultiply(getNumNodes(), maxThreadsPerNode));

    if (getInputCardinality() == 0) {
      Preconditions.checkState(cardinalityBasedCost.getTotalCost() == 0,
          "Scan is empty but cost is non-zero.");
      return cardinalityBasedCost;
    } else if (maxScannerThreads < cardinalityBasedCost.getTotalCost()
            / BackendConfig.INSTANCE.getMinProcessingPerThread()) {
      // Input cardinality is unknown or cost is too high compared to maxScanThreads
      // Return synthetic ProcessingCost based on maxScanThreads.
      long syntheticCardinality =
          Math.max(1, Math.min(getInputCardinality(), maxScannerThreads));
      long syntheticPerRowCost = LongMath.saturatedMultiply(
          Math.max(1,
              BackendConfig.INSTANCE.getMinProcessingPerThread() / syntheticCardinality),
          maxScannerThreads);

      return ProcessingCost.basicCost(
          getDisplayLabel(), syntheticCardinality, 0, syntheticPerRowCost);
    }

    // None of the conditions above apply.
    return cardinalityBasedCost;
  }

  /**
   * Estimate per-row cost as 1 per 1KB row size plus
   * (scan_range_cost_factor * min_processing_per_thread) for every scan ranges.
   * <p>
   * This reflects deserialization/copy cost per row and scan range open cost.
   */
  private float rowMaterializationCost() {
    float perRowCost = getAvgRowSize() / 1024;
    if (getInputCardinality() <= 0) return perRowCost;

    float scanRangeCostPerRow = BackendConfig.INSTANCE.getMinProcessingPerThread()
        * BackendConfig.INSTANCE.getScanRangeCostFactor() / getInputCardinality()
        * getEffectiveNumScanRanges();
    return perRowCost + scanRangeCostPerRow;
  }

  /**
   * Returns true if this node has conjuncts to be evaluated by Impala against the scan
   * tuple.
   */
  public boolean hasScanConjuncts() { return !getConjuncts().isEmpty(); }

  /**
   * Returns true if this node has conjuncts to be evaluated by the underlying storage
   * engine.
   */
  public boolean hasStorageLayerConjuncts() { return false; }

  public ExprSubstitutionMap getOptimizedAggSmap() { return optimizedAggSmap_; }

  protected long getEffectiveNumScanRanges() {
    Preconditions.checkNotNull(scanRangeSpecs_);
    return scanRangeSpecs_.getConcrete_rangesSize();
  }
}

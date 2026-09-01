package com.arextest.web.model.dao.mongodb;

import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.FieldNameConstants;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * Running totals of tagged-record storage usage per appId, combined across tag types. Maintained
 * via atomic $inc so reading/updating it stays O(1) regardless of how large case_tag grows - see
 * documents/proposals/case-tag-storage-limit-proposal.md §6.3/§6.3.3.
 */
@EqualsAndHashCode(callSuper = true)
@Data
@FieldNameConstants
@Document(collection = "case_tag_usage")
public class CaseTagUsageCollection extends ModelBase {

  @Indexed(unique = true)
  private String appId;

  private long usedBytes;

  private long usedCount;

  /**
   * The effective limit as of the last update (global default at time of writing - see
   * CaseTagService). Snapshotted here so a Grafana panel can read "used vs. limit" from this one
   * document without a join.
   */
  private long limitBytes;

  /**
   * usedBytes / limitBytes * 100, recomputed on every update.
   */
  private double usagePercent;
}

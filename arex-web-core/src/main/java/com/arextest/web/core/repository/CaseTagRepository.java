package com.arextest.web.core.repository;

import com.arextest.web.model.dto.CaseTagDto;
import java.util.List;
import java.util.Set;

public interface CaseTagRepository extends RepositoryProvider {

  /**
   * Return the recordIds already tagged for the given appId + tagType (optionally scoped to a
   * single planId). Used to make batch-add idempotent.
   */
  Set<String> queryRecordIds(String appId, String tagType, String planId);

  /**
   * Bulk insert tag entries. Returns the number of documents actually inserted.
   */
  int batchAdd(List<CaseTagDto> dtos);

  /**
   * Count how many records are already tagged for this appId + operationName, combined across
   * tag types. Used to enforce the per-operation count limit.
   */
  long countByAppIdAndOperationName(String appId, String operationName);
}

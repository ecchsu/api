package com.arextest.web.core.repository;

import com.arextest.web.model.dao.mongodb.CaseTagUsageCollection;

public interface CaseTagUsageRepository extends RepositoryProvider {

  /**
   * Current usage for appId, or null if this app has never tagged anything yet.
   */
  CaseTagUsageCollection findByAppId(String appId);

  /**
   * Atomically add deltaBytes/deltaCount to the app's running totals (upserting on the app's
   * first tag), then refresh limitBytes/usagePercent against the currently-effective limit.
   */
  void incrementUsage(String appId, long deltaBytes, long deltaCount, long effectiveLimitBytes);
}

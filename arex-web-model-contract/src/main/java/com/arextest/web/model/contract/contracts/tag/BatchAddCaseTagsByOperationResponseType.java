package com.arextest.web.model.contract.contracts.tag;

import lombok.Data;

@Data
public class BatchAddCaseTagsByOperationResponseType {

  private int tagged;

  /**
   * True when the whole request was rejected because the app was already at/over its combined
   * byte budget or record-count backstop before this batch started.
   */
  private boolean rejectedForQuota;
}

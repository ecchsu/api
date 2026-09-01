package com.arextest.web.model.contract.contracts.tag;

import lombok.Data;

@Data
public class BatchAddCaseTagsByOperationResponseType {

  private int tagged;

  /**
   * How many otherwise-eligible candidates were left untagged because the per-operation count
   * limit was already reached or would have been exceeded.
   */
  private int skippedForLimit;
}

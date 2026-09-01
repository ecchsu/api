package com.arextest.web.model.contract.contracts.tag;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class BatchAddCaseTagsByOperationRequestType {

  @NotBlank(message = "planId cannot be empty")
  private String planId;

  @NotBlank(message = "appId cannot be empty")
  private String appId;

  @NotBlank(message = "tagType cannot be empty")
  private String tagType;

  @NotBlank(message = "operationName cannot be empty")
  private String operationName;
}

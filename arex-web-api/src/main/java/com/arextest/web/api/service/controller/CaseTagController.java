package com.arextest.web.api.service.controller;

import com.arextest.common.annotation.AppAuth;
import com.arextest.common.enums.AuthRejectStrategy;
import com.arextest.common.model.response.Response;
import com.arextest.common.utils.ResponseUtils;
import com.arextest.web.core.business.CaseTagService;
import com.arextest.web.model.contract.contracts.tag.BatchAddCaseTagsByOperationRequestType;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Slf4j
@RestController
@RequestMapping("/api/tag")
@CrossOrigin(origins = "*", maxAge = 3600)
public class CaseTagController {

  @Resource
  private CaseTagService caseTagService;

  @PostMapping("/addByOperation")
  @AppAuth(rejectStrategy = AuthRejectStrategy.FAIL_RESPONSE)
  public Response addByOperation(
      @Valid @RequestBody BatchAddCaseTagsByOperationRequestType request) {
    return ResponseUtils.successResponse(caseTagService.batchAddByOperation(request));
  }
}

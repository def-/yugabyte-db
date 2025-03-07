/*
 * Copyright 2021 YugaByte, Inc. and Contributors
 *
 * Licensed under the Polyform Free Trial License 1.0.0 (the "License"); you
 * may not use this file except in compliance with the License. You
 * may obtain a copy of the License at
 *
 * http://github.com/YugaByte/yugabyte-db/blob/master/licenses/POLYFORM-FREE-TRIAL-LICENSE-1.0.0.txt
 */

package com.yugabyte.yw.controllers;

import com.fasterxml.jackson.databind.JsonNode;
import com.google.api.client.util.Throwables;
import com.google.inject.Inject;
import com.yugabyte.yw.commissioner.Common.CloudType;
import com.yugabyte.yw.commissioner.tasks.CloudBootstrap;
import com.yugabyte.yw.models.helpers.TaskType;
import com.yugabyte.yw.common.PlatformServiceException;
import com.yugabyte.yw.controllers.handlers.CloudProviderHandler;
import com.yugabyte.yw.forms.EditAccessKeyRotationScheduleParams;
import com.yugabyte.yw.forms.PlatformResults;
import com.yugabyte.yw.forms.PlatformResults.YBPSuccess;
import com.yugabyte.yw.forms.PlatformResults.YBPTask;
import com.yugabyte.yw.forms.RotateAccessKeyFormData;
import com.yugabyte.yw.forms.ScheduledAccessKeyRotateFormData;
import com.yugabyte.yw.models.Audit;
import com.yugabyte.yw.models.helpers.CloudInfoInterface;
import com.yugabyte.yw.models.Customer;
import com.yugabyte.yw.models.Provider;
import com.yugabyte.yw.models.Region;
import com.yugabyte.yw.models.Schedule;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiImplicitParam;
import io.swagger.annotations.ApiImplicitParams;
import io.swagger.annotations.ApiOperation;
import io.swagger.annotations.Authorization;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import play.libs.Json;
import play.mvc.Result;

@Api(
    value = "Cloud providers",
    authorizations = @Authorization(AbstractPlatformController.API_KEY_AUTH))
@Slf4j
public class CloudProviderApiController extends AuthenticatedController {

  @Inject private CloudProviderHandler cloudProviderHandler;

  @ApiOperation(
      value = "List cloud providers",
      response = Provider.class,
      responseContainer = "List",
      nickname = "getListOfProviders")
  public Result list(UUID customerUUID, String name, String code) {
    CloudType providerCode = code == null ? null : CloudType.valueOf(code);
    List<Provider> providers = Provider.getAll(customerUUID, name, providerCode);
    providers.forEach(CloudInfoInterface::mayBeMassageResponse);
    return PlatformResults.withData(providers);
  }

  @ApiOperation(value = "Get a cloud provider", response = Provider.class, nickname = "getProvider")
  public Result index(UUID customerUUID, UUID providerUUID) {
    Customer.getOrBadRequest(customerUUID);
    Provider provider = Provider.getOrBadRequest(customerUUID, providerUUID);
    CloudInfoInterface.mayBeMassageResponse(provider);
    return PlatformResults.withData(provider);
  }

  @ApiOperation(
      value = "Delete a cloud provider",
      notes = "This endpoint is used only for integration tests.",
      hidden = true,
      response = YBPSuccess.class)
  public Result delete(UUID customerUUID, UUID providerUUID) {
    Provider provider = Provider.getOrBadRequest(customerUUID, providerUUID);
    Customer customer = Customer.getOrBadRequest(customerUUID);
    cloudProviderHandler.delete(customer, provider);
    auditService()
        .createAuditEntryWithReqBody(
            ctx(),
            Audit.TargetType.CloudProvider,
            providerUUID.toString(),
            Audit.ActionType.Delete);
    return YBPSuccess.withMessage("Deleted provider: " + providerUUID);
  }

  @ApiOperation(
      value = "Refresh pricing",
      notes = "Refresh provider pricing info",
      response = YBPSuccess.class)
  public Result refreshPricing(UUID customerUUID, UUID providerUUID) {
    Provider provider = Provider.getOrBadRequest(customerUUID, providerUUID);
    cloudProviderHandler.refreshPricing(customerUUID, provider);
    auditService()
        .createAuditEntryWithReqBody(
            ctx(),
            Audit.TargetType.CloudProvider,
            providerUUID.toString(),
            Audit.ActionType.RefreshPricing);
    return YBPSuccess.withMessage(provider.code.toUpperCase() + " Initialized");
  }

  @ApiOperation(value = "Update a provider", response = YBPTask.class, nickname = "editProvider")
  @ApiImplicitParams(
      @ApiImplicitParam(
          value = "edit provider form data",
          name = "EditProviderRequest",
          dataType = "com.yugabyte.yw.models.Provider",
          required = true,
          paramType = "body"))
  public Result edit(UUID customerUUID, UUID providerUUID) {
    Customer customer = Customer.getOrBadRequest(customerUUID);
    Provider provider = Provider.getOrBadRequest(customerUUID, providerUUID);
    JsonNode requestBody = request().body().asJson();
    Provider editProviderReq = formFactory.getFormDataOrBadRequest(requestBody, Provider.class);
    UUID taskUUID =
        cloudProviderHandler.editProvider(
            customer, provider, editProviderReq, getFirstRegionCode(provider));
    auditService()
        .createAuditEntryWithReqBody(
            ctx(),
            Audit.TargetType.CloudProvider,
            providerUUID.toString(),
            Audit.ActionType.Update,
            Json.toJson(editProviderReq));
    return new YBPTask(taskUUID, providerUUID).asResult();
  }

  @ApiOperation(value = "Patch a provider", response = YBPTask.class, nickname = "patchProvider")
  @ApiImplicitParams(
      @ApiImplicitParam(
          value = "patch provider form data",
          name = "PatchProviderRequest",
          dataType = "com.yugabyte.yw.models.Provider",
          required = true,
          paramType = "body"))
  public Result patch(UUID customerUUID, UUID providerUUID) {
    Customer customer = Customer.getOrBadRequest(customerUUID);
    Provider provider = Provider.getOrBadRequest(customerUUID, providerUUID);
    JsonNode requestBody = request().body().asJson();
    Provider editProviderReq = formFactory.getFormDataOrBadRequest(requestBody, Provider.class);
    cloudProviderHandler.mergeProviderConfig(provider, editProviderReq);
    cloudProviderHandler.editProvider(
        customer, provider, editProviderReq, getFirstRegionCode(provider));
    auditService()
        .createAuditEntryWithReqBody(
            ctx(),
            Audit.TargetType.CloudProvider,
            providerUUID.toString(),
            Audit.ActionType.Update,
            Json.toJson(editProviderReq));
    return YBPSuccess.withMessage("Patched provider: " + providerUUID);
  }

  @ApiOperation(value = "Create a provider", response = YBPTask.class, nickname = "createProviders")
  @ApiImplicitParams(
      @ApiImplicitParam(
          name = "CreateProviderRequest",
          paramType = "body",
          dataType = "com.yugabyte.yw.models.Provider",
          required = true))
  public Result create(UUID customerUUID) {
    JsonNode requestBody = request().body().asJson();
    Provider reqProvider =
        formFactory.getFormDataOrBadRequest(request().body().asJson(), Provider.class);
    Customer customer = Customer.getOrBadRequest(customerUUID);
    reqProvider.customerUUID = customerUUID;
    CloudType providerCode = CloudType.valueOf(reqProvider.code);
    Provider providerEbean;
    if (providerCode.equals(CloudType.kubernetes)) {
      providerEbean = cloudProviderHandler.createKubernetesNew(customer, reqProvider);
    } else {
      providerEbean =
          cloudProviderHandler.createProvider(
              customer,
              providerCode,
              reqProvider.name,
              reqProvider,
              getFirstRegionCode(reqProvider));
    }

    if (providerCode.isRequiresBootstrap()) {
      UUID taskUUID = null;
      try {
        CloudBootstrap.Params taskParams = CloudBootstrap.Params.fromProvider(reqProvider);

        taskUUID = cloudProviderHandler.bootstrap(customer, providerEbean, taskParams);
        auditService()
            .createAuditEntryWithReqBody(
                ctx(),
                Audit.TargetType.CloudProvider,
                Objects.toString(providerEbean.uuid, null),
                Audit.ActionType.Create,
                requestBody,
                taskUUID);
      } catch (Throwable e) {
        log.warn("Bootstrap failed. Deleting provider");
        providerEbean.delete();
        Throwables.propagate(e);
      }
      return new YBPTask(taskUUID, providerEbean.uuid).asResult();
    } else {
      auditService()
          .createAuditEntryWithReqBody(
              ctx(),
              Audit.TargetType.CloudProvider,
              Objects.toString(providerEbean.uuid, null),
              Audit.ActionType.Create,
              requestBody,
              null);
      return new YBPTask(null, providerEbean.uuid).asResult();
    }
  }

  @ApiOperation(
      nickname = "accessKeyRotation",
      value = "Rotate access key for a provider",
      response = YBPTask.class)
  public Result accessKeysRotation(UUID customerUUID, UUID providerUUID) {
    RotateAccessKeyFormData params = parseJsonAndValidate(RotateAccessKeyFormData.class);
    Customer customer = Customer.getOrBadRequest(customerUUID);
    String newKeyCode = params.newKeyCode;
    boolean rotateAllUniverses = params.rotateAllUniverses;
    if (!rotateAllUniverses && params.universeUUIDs.size() == 0) {
      throw new PlatformServiceException(
          BAD_REQUEST,
          "Need to specify universeUUIDs"
              + " for access key rotation or set rotateAllUniverses to true!");
    }
    List<UUID> universeUUIDs =
        rotateAllUniverses
            ? customer
                .getUniversesForProvider(providerUUID)
                .stream()
                .map(universe -> universe.universeUUID)
                .collect(Collectors.toList())
            : params.universeUUIDs;

    Map<UUID, UUID> tasks =
        cloudProviderHandler.rotateAccessKeys(
            customerUUID, providerUUID, universeUUIDs, newKeyCode);

    // contains taskUUID and resourceUUID (universeUUID) for each universe
    List<YBPTask> tasksResponseList = new ArrayList<>();
    tasks.forEach(
        (universeUUID, taskUUID) -> {
          tasksResponseList.add(new YBPTask(taskUUID, universeUUID));
        });
    auditService()
        .createAuditEntryWithReqBody(
            ctx(),
            Audit.TargetType.CloudProvider,
            Objects.toString(providerUUID, null),
            Audit.ActionType.RotateAccessKey,
            request().body().asJson(),
            null);
    return PlatformResults.withData(tasksResponseList);
  }

  @ApiOperation(
      nickname = "scheduledAccessKeyRotation",
      value = "Rotate access key for a provider - Scheduled",
      response = Schedule.class)
  public Result scheduledAccessKeysRotation(UUID customerUUID, UUID providerUUID) {
    Customer customer = Customer.getOrBadRequest(customerUUID);
    ScheduledAccessKeyRotateFormData params =
        parseJsonAndValidate(ScheduledAccessKeyRotateFormData.class);
    int schedulingFrequencyDays = params.schedulingFrequencyDays;
    boolean rotateAllUniverses = params.rotateAllUniverses;
    if (!rotateAllUniverses && params.universeUUIDs.size() == 0) {
      throw new PlatformServiceException(
          BAD_REQUEST,
          "Need to specify universeUUIDs"
              + " to schedule access key rotation or set rotateAllUniverses to true!");
    }
    List<UUID> universeUUIDs =
        rotateAllUniverses
            ? customer
                .getUniversesForProvider(providerUUID)
                .stream()
                .map(universe -> universe.universeUUID)
                .collect(Collectors.toList())
            : params.universeUUIDs;
    Schedule schedule =
        cloudProviderHandler.scheduleAccessKeysRotation(
            customerUUID, providerUUID, universeUUIDs, schedulingFrequencyDays, rotateAllUniverses);
    UUID scheduleUUID = schedule.getScheduleUUID();
    log.info(
        "Created access key rotation schedule for customer {}, schedule uuid = {}.",
        customerUUID,
        scheduleUUID);
    auditService()
        .createAuditEntryWithReqBody(
            ctx(),
            Audit.TargetType.CloudProvider,
            Objects.toString(providerUUID, null),
            Audit.ActionType.CreateAndRotateAccessKey,
            request().body().asJson(),
            null);
    return PlatformResults.withData(schedule);
  }

  @ApiOperation(
      value = "List all schedules for a provider's access key rotation",
      response = Schedule.class,
      responseContainer = "List",
      nickname = "listSchedules")
  public Result listAccessKeyRotationSchedules(UUID customerUUID, UUID providerUUID) {
    Customer.getOrBadRequest(customerUUID);
    Provider.getOrBadRequest(customerUUID, providerUUID);
    List<Schedule> accessKeyRotationSchedules =
        Schedule.getAllByCustomerUUIDAndType(customerUUID, TaskType.CreateAndRotateAccessKey)
            .stream()
            .filter(schedule -> (schedule.getOwnerUUID().equals(providerUUID)))
            .collect(Collectors.toList());
    return PlatformResults.withData(accessKeyRotationSchedules);
  }

  private static String getFirstRegionCode(Provider provider) {
    for (Region r : provider.regions) {
      return r.code;
    }
    return null;
  }

  @ApiOperation(
      value = "Edit a access key rotation schedule",
      response = Schedule.class,
      nickname = "editAccessKeyRotationSchedule")
  @ApiImplicitParams({
    @ApiImplicitParam(
        required = true,
        dataType = "com.yugabyte.yw.forms.EditAccessKeyRotationScheduleParams",
        paramType = "body")
  })
  public Result editAccessKeyRotationSchedule(
      UUID customerUUID, UUID providerUUID, UUID scheduleUUID) {
    Customer.getOrBadRequest(customerUUID);
    Provider.getOrBadRequest(customerUUID, providerUUID);
    EditAccessKeyRotationScheduleParams params =
        parseJsonAndValidate(EditAccessKeyRotationScheduleParams.class);

    Schedule schedule =
        cloudProviderHandler.editAccessKeyRotationSchedule(
            customerUUID, providerUUID, scheduleUUID, params);

    auditService()
        .createAuditEntryWithReqBody(
            ctx(),
            Audit.TargetType.Schedule,
            scheduleUUID.toString(),
            Audit.ActionType.Edit,
            request().body().asJson());
    return PlatformResults.withData(schedule);
  }
}

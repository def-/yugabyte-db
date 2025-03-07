package com.yugabyte.yw.models.helpers.provider;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.yugabyte.yw.cloud.gcp.GCPCloudImpl;
import com.yugabyte.yw.controllers.handlers.CloudProviderHandler;
import com.yugabyte.yw.models.helpers.CloudInfoInterface;
import com.yugabyte.yw.models.helpers.CommonUtils;

import io.swagger.annotations.ApiModelProperty;
import io.swagger.annotations.ApiModelProperty.AccessMode;
import lombok.Data;

@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class GCPCloudInfo implements CloudInfoInterface {

  private static final Map<String, String> configKeyMap =
      ImmutableMap.of(
          "gceProject", "project_id",
          "gceApplicationCredentialsPath", "GOOGLE_APPLICATION_CREDENTIALS",
          "customGceNetwork", "network",
          "ybFirewallTags", CloudProviderHandler.YB_FIREWALL_TAGS,
          "useHostVPC", "use_host_vpc");

  private static final List<String> toRemoveKeyFromConfig =
      ImmutableList.of("gceApplicationCredentials", "useHostCredentials");

  private static final Map<String, String> toAddKeysInConfig =
      ImmutableMap.<String, String>builder()
          .put("client_email", "GCE_EMAIL")
          .put("project_id", "GCE_PROJECT")
          .put("auth_provider_x509_cert_url", "auth_provider_x509_cert_url")
          .put("auth_uri", "auth_uri")
          .put("client_id", "client_id")
          .put("client_x509_cert_url", "client_x509_cert_url")
          .put("private_key", "private_key")
          .put("private_key_id", "private_key_id")
          .put("token_uri", "token_uri")
          .put("type", "type")
          .build();

  private static final List<String> toMaskFieldsInCreds =
      ImmutableList.of("private_key", "private_key_id");

  @JsonAlias({"host_project_id", "project_id", GCPCloudImpl.GCE_PROJECT_PROPERTY})
  @ApiModelProperty
  public String gceProject;

  @JsonAlias({"config_file_path", GCPCloudImpl.GOOGLE_APPLICATION_CREDENTIALS_PROPERTY})
  @ApiModelProperty(accessMode = AccessMode.READ_ONLY)
  public String gceApplicationCredentialsPath;

  @JsonAlias("config_file_contents")
  @ApiModelProperty
  public JsonNode gceApplicationCredentials;

  @JsonAlias({"network", GCPCloudImpl.CUSTOM_GCE_NETWORK_PROPERTY})
  @ApiModelProperty
  public String customGceNetwork;

  @JsonAlias(CloudProviderHandler.YB_FIREWALL_TAGS)
  @ApiModelProperty
  public String ybFirewallTags;

  @JsonAlias("use_host_vpc")
  @ApiModelProperty
  public Boolean useHostVPC;

  @JsonAlias("use_host_credentials")
  @ApiModelProperty
  public Boolean useHostCredentials;

  @JsonIgnore
  public Map<String, String> getEnvVars() {
    Map<String, String> envVars = new HashMap<>();

    if (ybFirewallTags != null) {
      envVars.put(CloudProviderHandler.YB_FIREWALL_TAGS, ybFirewallTags);
    }
    if (gceProject != null) {
      envVars.put(GCPCloudImpl.GCE_PROJECT_PROPERTY, gceProject);
    }
    if (gceApplicationCredentialsPath != null) {
      envVars.put(
          GCPCloudImpl.GOOGLE_APPLICATION_CREDENTIALS_PROPERTY, gceApplicationCredentialsPath);
    }
    if (customGceNetwork != null) {
      envVars.put(GCPCloudImpl.CUSTOM_GCE_NETWORK_PROPERTY, customGceNetwork);
    }

    return envVars;
  }

  @JsonIgnore
  public Map<String, String> getConfigMapForUIOnlyAPIs(Map<String, String> config) {
    for (Map.Entry<String, String> entry : configKeyMap.entrySet()) {
      if (config.get(entry.getKey()) != null) {
        config.put(entry.getValue(), config.get(entry.getKey()));
        config.remove(entry.getKey());
      }
    }

    for (String removeKey : toRemoveKeyFromConfig) {
      config.remove(removeKey);
    }

    if (gceApplicationCredentials == null) {
      return config;
    }
    ObjectNode credentialJSON = (ObjectNode) gceApplicationCredentials;
    for (Map.Entry<String, String> entry : toAddKeysInConfig.entrySet()) {
      if (credentialJSON.get(entry.getKey()) != null) {
        config.put(entry.getValue(), credentialJSON.get(entry.getKey()).toString());
      }
    }

    return config;
  }

  @JsonIgnore
  public void withSensitiveDataMasked() {
    this.gceApplicationCredentialsPath = CommonUtils.getMaskedValue(gceApplicationCredentialsPath);
    this.gceApplicationCredentials =
        CommonUtils.getMaskedValue(gceApplicationCredentials, toMaskFieldsInCreds);
  }
}

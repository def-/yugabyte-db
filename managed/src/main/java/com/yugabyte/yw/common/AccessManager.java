// Copyright (c) YugaByte, Inc.

package com.yugabyte.yw.common;

import static play.mvc.Http.Status.INTERNAL_SERVER_ERROR;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.google.inject.Inject;
import com.yugabyte.yw.commissioner.Commissioner;
import com.yugabyte.yw.commissioner.Common;
import com.yugabyte.yw.commissioner.tasks.params.RotateAccessKeyParams;
import com.yugabyte.yw.common.utils.FileUtils;
import com.yugabyte.yw.models.AccessKey;
import com.yugabyte.yw.models.AccessKeyId;
import com.yugabyte.yw.models.Customer;
import com.yugabyte.yw.models.CustomerTask;
import com.yugabyte.yw.models.FileData;
import com.yugabyte.yw.models.Provider;
import com.yugabyte.yw.models.ProviderDetails;
import com.yugabyte.yw.models.Region;
import com.yugabyte.yw.models.Universe;
import com.yugabyte.yw.models.helpers.TaskType;
import io.ebean.annotation.Transactional;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;

@Singleton
@Slf4j
public class AccessManager extends DevopsBase {

  private final play.Configuration appConfig;
  private final Commissioner commissioner;

  private static final String YB_CLOUD_COMMAND_TYPE = "access";
  public static final String PEM_PERMISSIONS = "r--------";
  public static final String PUB_PERMISSIONS = "rw-r--r--";
  private static final String KUBECONFIG_PERMISSIONS = "rw-------";
  public static final String STORAGE_PATH = "yb.storage.path";

  @Inject
  public AccessManager(play.Configuration appConfig, Commissioner commissioner) {
    this.appConfig = appConfig;
    this.commissioner = commissioner;
  }

  @Override
  protected String getCommandType() {
    return YB_CLOUD_COMMAND_TYPE;
  }

  public enum KeyType {
    PUBLIC,
    PRIVATE;

    public String getExtension() {
      switch (this) {
        case PUBLIC:
          return ".pub";
        case PRIVATE:
          return ".pem";
        default:
          return null;
      }
    }
  }

  @Transactional
  private void writeKeyFileData(AccessKey.KeyInfo keyInfo) {
    FileData.writeFileToDB(keyInfo.vaultFile);
    FileData.writeFileToDB(keyInfo.vaultPasswordFile);
    if (keyInfo.privateKey != null) {
      FileData.writeFileToDB(keyInfo.privateKey);
    }
    if (keyInfo.publicKey != null) {
      FileData.writeFileToDB(keyInfo.publicKey);
    }
  }

  public String getOrCreateKeyFilePath(UUID providerUUID) {
    File keyBasePathName = new File(appConfig.getString(STORAGE_PATH), "/keys");
    // Protect against multi-threaded access and validate that we only error out if mkdirs fails
    // correctly, by NOT creating the final dir path.
    synchronized (this) {
      if (!keyBasePathName.exists() && !keyBasePathName.mkdirs() && !keyBasePathName.exists()) {
        throw new RuntimeException(
            "Key path " + keyBasePathName.getAbsolutePath() + " doesn't exist.");
      }
    }

    File keyFilePath = new File(keyBasePathName.getAbsoluteFile(), providerUUID.toString());
    if (keyFilePath.isDirectory() || keyFilePath.mkdirs()) {
      return keyFilePath.getAbsolutePath();
    }

    throw new RuntimeException("Unable to create key file path " + keyFilePath.getAbsolutePath());
  }

  private String getOrCreateKeyFilePath(String path) {
    File keyBasePathName = new File(appConfig.getString(STORAGE_PATH), "/keys");
    // Protect against multi-threaded access and validate that we only error out if mkdirs fails
    // correctly, by NOT creating the final dir path.
    synchronized (this) {
      if (!keyBasePathName.exists() && !keyBasePathName.mkdirs() && !keyBasePathName.exists()) {
        throw new RuntimeException(
            "Key path " + keyBasePathName.getAbsolutePath() + " doesn't exist.");
      }
    }
    File keyFilePath = new File(keyBasePathName.getAbsoluteFile(), path);
    if (keyFilePath.isDirectory() || keyFilePath.mkdirs()) {
      return keyFilePath.getAbsolutePath();
    }
    throw new RuntimeException("Unable to create key file path " + keyFilePath.getAbsolutePath());
  }

  public AccessKey uploadKeyFile(
      UUID regionUUID,
      File uploadedFile,
      String keyCode,
      KeyType keyType,
      String sshUser,
      Integer sshPort,
      boolean airGapInstall,
      boolean skipProvisioning,
      boolean setUpChrony,
      List<String> ntpServers,
      boolean showSetUpChrony)
      throws IOException {
    return uploadKeyFile(
        regionUUID,
        uploadedFile,
        keyCode,
        keyType,
        sshUser,
        sshPort,
        airGapInstall,
        skipProvisioning,
        setUpChrony,
        ntpServers,
        showSetUpChrony,
        true);
  }

  // This method would upload the provided key file to the provider key file path.
  public AccessKey uploadKeyFile(
      UUID regionUUID,
      File uploadedFile,
      String keyCode,
      KeyType keyType,
      String sshUser,
      Integer sshPort,
      boolean airGapInstall,
      boolean skipProvisioning,
      boolean setUpChrony,
      List<String> ntpServers,
      boolean showSetUpChrony,
      boolean deleteRemote)
      throws IOException {
    Region region = Region.get(regionUUID);
    final Provider provider = region.provider;
    String keyFilePath = getOrCreateKeyFilePath(provider.uuid);
    // Removing paths from keyCode.
    keyCode = FileUtils.getFileName(keyCode);
    AccessKey accessKey = AccessKey.get(provider.uuid, keyCode);
    if (accessKey != null) {
      // This means the key must have been created before, so nothing to do.
      return accessKey;
    }
    Path source = Paths.get(uploadedFile.getAbsolutePath());
    Path destination = Paths.get(keyFilePath, keyCode + keyType.getExtension());
    if (!Files.exists(source)) {
      throw new PlatformServiceException(
          INTERNAL_SERVER_ERROR, "Key file " + source.getFileName() + " not found.");
    }
    if (Files.exists(destination)) {
      throw new PlatformServiceException(
          INTERNAL_SERVER_ERROR, "File " + destination.getFileName() + " already exists.");
    }

    Files.move(source, destination);
    Set<PosixFilePermission> permissions = PosixFilePermissions.fromString(PEM_PERMISSIONS);
    if (keyType == AccessManager.KeyType.PUBLIC) {
      permissions = PosixFilePermissions.fromString(PUB_PERMISSIONS);
    }
    Files.setPosixFilePermissions(destination, permissions);

    AccessKey.KeyInfo keyInfo = new AccessKey.KeyInfo();
    if (keyType == AccessManager.KeyType.PUBLIC) {
      keyInfo.publicKey = destination.toAbsolutePath().toString();
    } else {
      keyInfo.privateKey = destination.toAbsolutePath().toString();
    }
    JsonNode vaultResponse = createVault(regionUUID, keyInfo.privateKey);
    if (vaultResponse.has("error")) {
      throw new PlatformServiceException(
          INTERNAL_SERVER_ERROR,
          "Vault Creation failed with : " + vaultResponse.get("error").asText());
    }
    keyInfo.vaultFile = vaultResponse.get("vault_file").asText();
    keyInfo.vaultPasswordFile = vaultResponse.get("vault_password").asText();
    keyInfo.deleteRemote = deleteRemote;

    // TODO: Move this code for ProviderDetails update elsewhere
    ProviderDetails details = provider.details;
    details.sshUser = sshUser;
    details.sshPort = sshPort;
    details.airGapInstall = airGapInstall;
    details.skipProvisioning = skipProvisioning;
    details.ntpServers = ntpServers;
    details.setUpChrony = setUpChrony;
    details.showSetUpChrony = showSetUpChrony;
    provider.save();

    writeKeyFileData(keyInfo);

    return AccessKey.create(provider.uuid, keyCode, keyInfo);
  }

  public AccessKey saveAndAddKey(
      UUID regionUUID,
      String keyContents,
      String keyCode,
      KeyType keyType,
      String sshUser,
      Integer sshPort,
      boolean airGapInstall,
      boolean skipProvisioning,
      boolean setUpChrony,
      List<String> ntpServers,
      boolean showSetUpChrony,
      boolean skipKeyPairValidate) {
    AccessKey key = null;
    Path tempFile = null;

    try {
      tempFile = Files.createTempFile(keyCode, keyType.getExtension());
      Files.write(tempFile, keyContents.getBytes());

      // Initially set delete to false because we don't know if this KeyPair exists in AWS
      key =
          uploadKeyFile(
              regionUUID,
              tempFile.toFile(),
              keyCode,
              keyType,
              sshUser,
              sshPort,
              airGapInstall,
              skipProvisioning,
              setUpChrony,
              ntpServers,
              showSetUpChrony,
              false);

      File pemFile = new File(key.getKeyInfo().privateKey);
      // Delete is always false and we don't even try to make AWS calls.
      if (!skipKeyPairValidate) {
        key =
            addKey(
                regionUUID,
                keyCode,
                pemFile,
                sshUser,
                sshPort,
                airGapInstall,
                skipProvisioning,
                setUpChrony,
                ntpServers,
                showSetUpChrony);
      }
    } catch (NoSuchFileException ioe) {
      log.error(ioe.getMessage(), ioe);
    } catch (IOException ioe) {
      log.error(ioe.getMessage(), ioe);
      throw new RuntimeException("Could not create AccessKey", ioe);
    } finally {
      try {
        if (tempFile != null) {
          Files.delete(tempFile);
        }
      } catch (IOException e) {
        log.error(e.getMessage(), e);
      }
    }

    return key;
  }
  // This method would create a public/private key file and upload that to
  // the provider cloud account. And store the credentials file in the keyFilePath
  // and return the file names. It will also create the vault file.
  public AccessKey addKey(
      UUID regionUUID,
      String keyCode,
      Integer sshPort,
      boolean airGapInstall,
      boolean skipProvisioning,
      boolean setUpChrony,
      List<String> ntpServers,
      boolean showSetupChrony) {
    return addKey(
        regionUUID,
        keyCode,
        null,
        null,
        sshPort,
        airGapInstall,
        skipProvisioning,
        setUpChrony,
        ntpServers,
        showSetupChrony);
  }

  public AccessKey addKey(
      UUID regionUUID,
      String keyCode,
      File privateKeyFile,
      String sshUser,
      Integer sshPort,
      boolean airGapInstall) {
    return addKey(
        regionUUID,
        keyCode,
        privateKeyFile,
        sshUser,
        sshPort,
        airGapInstall,
        false,
        false,
        null,
        false);
  }

  public AccessKey addKey(
      UUID regionUUID,
      String keyCode,
      Integer sshPort,
      boolean setUpChrony,
      List<String> ntpServers) {
    return addKey(
        regionUUID, keyCode, null, null, sshPort, false, false, setUpChrony, ntpServers, false);
  }

  public AccessKey addKey(
      UUID regionUUID,
      String keyCode,
      File privateKeyFile,
      String sshUser,
      Integer sshPort,
      boolean airGapInstall,
      boolean skipProvisioning,
      boolean setUpChrony,
      List<String> ntpServers,
      boolean showSetupChrony) {
    List<String> commandArgs = new ArrayList<String>();
    Region region = Region.get(regionUUID);
    String keyFilePath = getOrCreateKeyFilePath(region.provider.uuid);

    AccessKey accessKey = AccessKey.get(region.provider.uuid, keyCode);

    commandArgs.add("--key_pair_name");
    commandArgs.add(keyCode);
    commandArgs.add("--key_file_path");
    commandArgs.add(keyFilePath);

    String privateKeyFilePath = null;
    if (accessKey != null && accessKey.getKeyInfo().privateKey != null) {
      privateKeyFilePath = accessKey.getKeyInfo().privateKey;
    } else if (privateKeyFile != null) {
      privateKeyFilePath = privateKeyFile.getAbsolutePath();
    }
    // If we have a private key file to use, add in the param.
    if (privateKeyFilePath != null) {
      commandArgs.add("--private_key_file");
      commandArgs.add(privateKeyFilePath);
    }

    JsonNode response = execAndParseCommandRegion(regionUUID, "add-key", commandArgs);
    if (response.has("error")) {
      throw new PlatformServiceException(
          INTERNAL_SERVER_ERROR,
          "Parsing of Region failed with : " + response.get("error").asText());
    }

    if (accessKey == null) {
      AccessKey.KeyInfo keyInfo = new AccessKey.KeyInfo();
      keyInfo.publicKey = response.get("public_key").asText();
      keyInfo.privateKey = response.get("private_key").asText();
      JsonNode vaultResponse = createVault(regionUUID, keyInfo.privateKey);
      if (response.has("error")) {
        throw new PlatformServiceException(
            INTERNAL_SERVER_ERROR,
            "Vault Creation failed with : " + response.get("error").asText());
      }
      keyInfo.vaultFile = vaultResponse.get("vault_file").asText();
      keyInfo.vaultPasswordFile = vaultResponse.get("vault_password").asText();
      if (sshUser != null) {
        region.provider.details.sshUser = sshUser;
      } else {
        switch (Common.CloudType.valueOf(region.provider.code)) {
          case aws:
          case azu:
          case gcp:
            String defaultSshUser = Common.CloudType.valueOf(region.provider.code).getSshUser();
            if (defaultSshUser != null && !defaultSshUser.isEmpty()) {
              region.provider.details.sshUser = defaultSshUser;
            }
        }
      }
      region.provider.details.sshPort = sshPort;
      region.provider.details.airGapInstall = airGapInstall;
      region.provider.details.skipProvisioning = skipProvisioning;
      region.provider.details.setUpChrony = setUpChrony;
      region.provider.details.ntpServers = ntpServers;
      region.provider.details.showSetUpChrony = showSetupChrony;
      writeKeyFileData(keyInfo);
      accessKey = AccessKey.create(region.provider.uuid, keyCode, keyInfo);
      region.provider.save();
    }

    // Save if key needs to be deleted
    AccessKey.KeyInfo keyInfo = accessKey.getKeyInfo();
    if (response.has("delete_remote")) {
      keyInfo.deleteRemote = response.get("delete_remote").asBoolean();
      accessKey.setKeyInfo(keyInfo);
      accessKey.save();
    }

    return accessKey;
  }

  public JsonNode createVault(UUID regionUUID, String privateKeyFile) {
    List<String> commandArgs = new ArrayList<String>();

    if (!new File(privateKeyFile).exists()) {
      throw new RuntimeException("File " + privateKeyFile + " doesn't exists.");
    }
    commandArgs.add("--private_key_file");
    commandArgs.add(privateKeyFile);
    return execAndParseCommandRegion(regionUUID, "create-vault", commandArgs);
  }

  public JsonNode listKeys(UUID regionUUID) {
    return execAndParseCommandRegion(regionUUID, "list-keys", Collections.emptyList());
  }

  public JsonNode deleteKey(UUID regionUUID, String keyCode) {
    Region region = Region.get(regionUUID);
    if (region == null) {
      throw new RuntimeException("Invalid Region UUID: " + regionUUID);
    }

    switch (Common.CloudType.valueOf(region.provider.code)) {
      case aws:
      case azu:
      case gcp:
      case onprem:
        return deleteKey(region.provider.uuid, region.uuid, keyCode);
      default:
        return null;
    }
  }

  public JsonNode deleteKeyByProvider(Provider provider, String keyCode, boolean deleteRemote) {
    List<Region> regions = Region.getByProvider(provider.uuid);
    if (regions == null || regions.isEmpty()) {
      return null;
    }

    if (Common.CloudType.valueOf(provider.code) == Common.CloudType.aws) {
      ObjectMapper mapper = play.libs.Json.newDefaultMapper();
      ArrayNode ret = mapper.getNodeFactory().arrayNode();
      regions
          .stream()
          .map(r -> deleteKey(provider.uuid, r.uuid, keyCode, deleteRemote))
          .collect(Collectors.toList())
          .forEach(ret::add);
      return ret;
    } else {
      return deleteKey(provider.uuid, regions.get(0).uuid, keyCode, deleteRemote);
    }
  }

  private JsonNode deleteKey(UUID providerUUID, UUID regionUUID, String keyCode) {
    return deleteKey(providerUUID, regionUUID, keyCode, true);
  }

  private JsonNode deleteKey(
      UUID providerUUID, UUID regionUUID, String keyCode, boolean deleteRemote) {
    List<String> commandArgs = new ArrayList<String>();
    String keyFilePath = getOrCreateKeyFilePath(providerUUID);

    commandArgs.add("--key_pair_name");
    commandArgs.add(keyCode);
    commandArgs.add("--key_file_path");
    commandArgs.add(keyFilePath);
    if (deleteRemote) {
      commandArgs.add("--delete_remote");
    }
    commandArgs.add("--ignore_auth_failure");
    JsonNode response = execAndParseCommandRegion(regionUUID, "delete-key", commandArgs);
    if (response.has("error")) {
      throw new RuntimeException(response.get("error").asText());
    }
    return response;
  }

  public String createGCPCredentialsFile(UUID providerUUID, JsonNode credentials) {
    try {
      ObjectMapper mapper = new ObjectMapper();
      String credentialsFilePath = getOrCreateKeyFilePath(providerUUID) + "/credentials.json";
      mapper.writeValue(new File(credentialsFilePath), credentials);
      FileData.writeFileToDB(credentialsFilePath);
      return credentialsFilePath;
    } catch (Exception e) {
      throw new RuntimeException("Failed to create credentials file", e);
    }
  }

  public Map<String, String> readCredentialsFromFile(UUID providerUUID) {
    try {
      String credentialsFilePath = getOrCreateKeyFilePath(providerUUID) + "/credentials.json";
      byte[] bytes = Files.readAllBytes(Paths.get(credentialsFilePath));
      return new ObjectMapper().readValue(bytes, new TypeReference<Map<String, String>>() {});
    } catch (Exception e) {
      throw new RuntimeException("Failed to read credentials file", e);
    }
  }

  public String createKubernetesConfig(String path, Map<String, String> config, boolean edit) {
    // Grab the kubernetes config file name and file content and create the physical file.
    String configFileName = config.remove("KUBECONFIG_NAME");
    String configFileContent = config.remove("KUBECONFIG_CONTENT");

    // In case of edit, don't throw exception if conf file isn't provided.
    if (edit && (configFileName == null || configFileContent == null)) {
      return null;
    }

    if (configFileName == null) {
      throw new RuntimeException("Missing KUBECONFIG_NAME data in the provider config.");
    } else if (configFileContent == null) {
      throw new RuntimeException("Missing KUBECONFIG_CONTENT data in the provider config.");
    }
    String configFilePath = getOrCreateKeyFilePath(path);
    Path configFile = Paths.get(configFilePath, FileUtils.getFileName(configFileName));
    if (!edit && Files.exists(configFile)) {
      throw new RuntimeException("File " + configFile.getFileName() + " already exists.");
    }

    Set<PosixFilePermission> ownerRW = PosixFilePermissions.fromString(KUBECONFIG_PERMISSIONS);
    try {
      Files.write(configFile, configFileContent.getBytes());
      Files.setPosixFilePermissions(configFile, ownerRW);
      FileData.writeFileToDB(configFile.toAbsolutePath().toString());
      return configFile.toAbsolutePath().toString();
    } catch (Exception e) {
      throw new RuntimeException("Failed to create kubernetes config", e);
    }
  }

  public String createPullSecret(UUID providerUUID, Map<String, String> config, boolean edit) {
    // Grab the kubernetes config file name and file content and create the physical file.
    String pullSecretFileName = config.remove("KUBECONFIG_PULL_SECRET_NAME");
    String pullSecretFileContent = config.remove("KUBECONFIG_PULL_SECRET_CONTENT");
    if (pullSecretFileName == null) {
      throw new RuntimeException(
          "Missing KUBECONFIG_PULL_SECRET_NAME data in the provider config.");
    } else if (pullSecretFileContent == null) {
      throw new RuntimeException(
          "Missing KUBECONFIG_PULL_SECRET_CONTENT data in the provider config.");
    }
    String pullSecretFilePath = getOrCreateKeyFilePath(providerUUID);
    Path pullSecretFile = Paths.get(pullSecretFilePath, FileUtils.getFileName(pullSecretFileName));
    if (!edit && Files.exists(pullSecretFile)) {
      throw new RuntimeException("File " + pullSecretFile.getFileName() + " already exists.");
    }
    try {
      Files.write(pullSecretFile, pullSecretFileContent.getBytes());
      FileData.writeFileToDB(pullSecretFile.toAbsolutePath().toString());
      return pullSecretFile.toAbsolutePath().toString();
    } catch (Exception e) {
      throw new RuntimeException("Failed to create pull secret", e);
    }
  }

  public Map<UUID, UUID> rotateAccessKey(
      UUID customerUUID, UUID providerUUID, List<UUID> universeUUIDs, String newKeyCode) {
    Customer customer = Customer.getOrBadRequest(customerUUID);
    AccessKey newAccessKey = AccessKey.getOrBadRequest(providerUUID, newKeyCode);
    // request would fail if there is a universe which does not exist
    Set<Universe> universes =
        universeUUIDs
            .stream()
            .map((universeUUID) -> Universe.getOrBadRequest(universeUUID))
            .collect(Collectors.toSet());
    Set<Universe> providerUniverses = customer.getUniversesForProvider(providerUUID);

    // check if all universes belong to the provider
    if (universes.stream().anyMatch((universe) -> !providerUniverses.contains(universe))) {
      throw new RuntimeException("One of the universes does not belong to the provider");
    }
    Map<UUID, UUID> taskUUIDs = new HashMap<UUID, UUID>();
    for (Universe universe : universes) {
      // create universe task params
      UUID universeUUID = universe.universeUUID;
      RotateAccessKeyParams taskParams =
          new RotateAccessKeyParams(customerUUID, providerUUID, universeUUID, newAccessKey);
      // trigger universe task
      UUID taskUUID = commissioner.submit(TaskType.RotateAccessKey, taskParams);
      CustomerTask.create(
          customer,
          universeUUID,
          taskUUID,
          CustomerTask.TargetType.Universe,
          CustomerTask.TaskType.RotateAccessKey,
          universe.name);
      taskUUIDs.put(universeUUID, taskUUID);
    }
    return taskUUIDs;
  }

  /**
   * Checks whether the private access keys have the valid permission.
   *
   * @return true if access keys permission is unchanged.
   */
  public boolean checkAccessKeyPermissionsValidity(
      Universe universe, Map<AccessKeyId, AccessKey> allAccessKeysMap) {
    return universe
        .getUniverseDetails()
        .clusters
        .stream()
        .noneMatch(
            cluster -> {
              String keyCode = cluster.userIntent.accessKeyCode;
              UUID providerUUID = UUID.fromString(cluster.userIntent.provider);
              if (!StringUtils.isEmpty(keyCode)) {
                AccessKeyId id = AccessKeyId.create(providerUUID, keyCode);
                AccessKey accessKey = allAccessKeysMap.get(id);
                String keyFilePath = accessKey.getKeyInfo().privateKey;
                try {
                  String permissions =
                      PosixFilePermissions.toString(
                          Files.getPosixFilePermissions(Paths.get(keyFilePath)));
                  if (!permissions.equals(PEM_PERMISSIONS)) {
                    return true;
                  }
                } catch (IOException e) {
                  log.error("Error while fetching permissions of access key: {}", keyFilePath, e);
                  return true;
                }
              }
              return false;
            });
  }
}

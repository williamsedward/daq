package com.google.daq.mqtt.registrar;

import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.util.StdDateFormat;
import com.google.api.services.cloudiot.v1.model.Device;
import com.google.api.services.cloudiot.v1.model.DeviceCredential;
import com.google.common.base.Preconditions;
import com.google.daq.mqtt.util.*;
import com.google.daq.mqtt.util.ExceptionMap.ErrorTree;
import org.everit.json.schema.Schema;
import org.everit.json.schema.loader.SchemaClient;
import org.everit.json.schema.loader.SchemaLoader;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.math.BigInteger;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static java.util.stream.Collectors.toSet;

public class Registrar {

  static final String METADATA_JSON = "metadata.json";
  static final String DEVICE_ERRORS_JSON = "errors.json";
  static final String ENVELOPE_JSON = "envelope.json";
  static final String PROPERTIES_JSON = "properties.json";

  private static final String DEVICES_DIR = "devices";
  private static final String ERROR_FORMAT_INDENT = "  ";

  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper()
      .enable(SerializationFeature.INDENT_OUTPUT)
      .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
      .setDateFormat(new StdDateFormat())
      .setSerializationInclusion(Include.NON_NULL);
  public static final String ALL_MATCH = "";

  private String gcpCredPath;
  private CloudIotManager cloudIotManager;
  private File siteConfig;
  private final Map<String, Schema> schemas = new HashMap<>();
  private File schemaBase;
  private String schemaName;
  private PubSubPusher pubSubPusher;
  private Map<String, LocalDevice> localDevices;
  private File summaryFile;

  public static void main(String[] args) {
    Registrar registrar = new Registrar();
    try {
      if (args.length < 3 || args.length > 4) {
        throw new IllegalArgumentException("Args: [gcp_cred_file] [site_dir] [schema_file] (device_regex)");
      }
      registrar.setSchemaBase(args[2]);
      registrar.setGcpCredPath(args[0]);
      registrar.setSiteConfigPath(args[1]);
      registrar.processDevices(args.length > 3 ? args[3] : ALL_MATCH);
      registrar.writeErrors();
      registrar.shutdown();
    } catch (ExceptionMap em) {
      ErrorTree errorTree = ExceptionMap.format(em, ERROR_FORMAT_INDENT);
      errorTree.write(System.err);
      System.exit(2);
    } catch (Exception ex) {
      ex.printStackTrace();
      System.exit(-1);
    }
    System.exit(0);
  }

  private void writeErrors() throws Exception {
    Map<String, Map<String, String>> errorSummary = new TreeMap<>();
    localDevices.values().forEach(LocalDevice::writeErrors);
    localDevices.values().forEach(device -> {
      device.getErrors().stream().forEach(error -> errorSummary
          .computeIfAbsent(error.getKey(), cat -> new TreeMap<>())
          .put(device.getDeviceId(), error.getValue().toString()));
      if (device.getErrors().isEmpty()) {
        errorSummary.computeIfAbsent("Clean", cat -> new TreeMap<>())
            .put(device.getDeviceId(), "True");
      }
    });
    errorSummary.forEach((key, value) -> System.err.println("Device " + key + ": " + value.size()));
    System.err.println("Out of " + localDevices.size() + " total.");
    OBJECT_MAPPER.writeValue(summaryFile, errorSummary);
  }

  private void setSiteConfigPath(String siteConfigPath) {
    Preconditions.checkNotNull(schemaName, "schemaName not set yet");
    siteConfig = new File(siteConfigPath);
    summaryFile = new File(siteConfig, "registration_summary.json");
    summaryFile.delete();
    File cloudIotConfig = new File(siteConfig, ConfigUtil.CLOUD_IOT_CONFIG_JSON);
    System.err.println("Reading Cloud IoT config from " + cloudIotConfig.getAbsolutePath());
    cloudIotManager = new CloudIotManager(new File(gcpCredPath), cloudIotConfig, schemaName);
    pubSubPusher = new PubSubPusher(new File(gcpCredPath), cloudIotConfig);
    System.err.println(String.format("Working with project %s registry %s",
        cloudIotManager.getProjectId(), cloudIotManager.getRegistryId()));
  }

  private void processDevices(String deviceRegex) {
    try {
      Pattern devicePattern = Pattern.compile(deviceRegex);
      localDevices = loadLocalDevices(devicePattern);
      List<Device> cloudDevices = fetchDeviceList(devicePattern);
      Set<String> extraDevices = cloudDevices.stream().map(Device::getId).collect(toSet());
      for (String localName : localDevices.keySet()) {
        extraDevices.remove(localName);
        LocalDevice localDevice = localDevices.get(localName);
        try {
          updateCloudIoT(localDevice);
          Device device = Preconditions.checkNotNull(fetchDevice(localName),
              "missing device " + localName);
          BigInteger numId = Preconditions.checkNotNull(device.getNumId(),
              "missing deviceNumId for " + localName);
          localDevice.setDeviceNumId(numId.toString());
          sendMetadataMessage(localDevice);
        } catch (Exception e) {
          System.err.println("Deferring exception: " + e.toString());
          localDevice.getErrors().put("Registering", e);
        }
      }
      for (String extraName : extraDevices) {
        try {
          System.err.println("Blocking extra device " + extraName);
          cloudIotManager.blockDevice(extraName, true);
        } catch (Exception e) {
          throw new RuntimeException("While blocking " + extraName, e);
        }
      }
      System.err.println(String.format("Processed %d devices", localDevices.size()));
    } catch (Exception e) {
      throw new RuntimeException("While processing devices", e);
    }
  }

  private Device fetchDevice(String localName) {
    try {
      return cloudIotManager.fetchDevice(localName);
    } catch (Exception e) {
      throw new RuntimeException("Fetching device " + localName, e);
    }
  }

  private void sendMetadataMessage(LocalDevice localDevice) {
    System.err.println("Sending metadata message for " + localDevice.getDeviceId());
    Map<String, String> attributes = new HashMap<>();
    attributes.put("deviceId", localDevice.getDeviceId());
    attributes.put("deviceNumId", localDevice.getDeviceNumId());
    attributes.put("deviceRegistryId", cloudIotManager.getRegistryId());
    attributes.put("projectId", cloudIotManager.getProjectId());
    attributes.put("subFolder", LocalDevice.METADATA_SUBFOLDER);
    pubSubPusher.sendMessage(attributes, localDevice.getSettings().metadata);
  }

  private void updateCloudIoT(LocalDevice localDevice) {
    String localName = localDevice.getDeviceId();
    fetchDevice(localName);
    CloudDeviceSettings localDeviceSettings = localDevice.getSettings();
    if (cloudIotManager.registerDevice(localName, localDeviceSettings)) {
      System.err.println("Created new device entry " + localName);
    } else {
      System.err.println("Updated device entry " + localName);
    }
  }

  private void shutdown() {
    pubSubPusher.shutdown();
  }

  private List<Device> fetchDeviceList(Pattern devicePattern) {
    System.err.println("Fetching remote registry " + cloudIotManager.getRegistryId());
    return cloudIotManager.fetchDeviceList(devicePattern);
  }

  private Map<String,LocalDevice> loadLocalDevices(Pattern devicePattern) {
    File devicesDir = new File(siteConfig, DEVICES_DIR);
    String[] devices = devicesDir.list();
    Preconditions.checkNotNull(devices, "No devices found in " + devicesDir.getAbsolutePath());
    Map<String, LocalDevice> localDevices = loadDevices(devicesDir, devices, devicePattern);
    validateKeys(localDevices);
    validateFiles(localDevices);
    writeNormalized(localDevices);
    return localDevices;
  }

  private void validateFiles(Map<String, LocalDevice> localDevices) {
    for (LocalDevice device : localDevices.values()) {
      try {
        device.validatedDeviceDir();
      } catch (Exception e) {
        device.getErrors().put("Files", e);
      }
    }
  }

  private void writeNormalized(Map<String, LocalDevice> localDevices) {
    for (String deviceName : localDevices.keySet()) {
      try {
        System.err.println("Writing normalized device " + deviceName);
        localDevices.get(deviceName).writeNormalized();
      } catch (Exception e) {
        throw new RuntimeException("While writing normalized " + deviceName, e);
      }
    }
  }

  private void validateKeys(Map<String, LocalDevice> localDevices) {
    Map<DeviceCredential, String> privateKeys = new HashMap<>();
    for (LocalDevice device : localDevices.values()) {
      String deviceName = device.getDeviceId();
      CloudDeviceSettings settings = device.getSettings();
      if (privateKeys.containsKey(settings.credential)) {
        String previous = privateKeys.get(settings.credential);
        RuntimeException exception = new RuntimeException(
            String.format("Duplicate credentials found for %s & %s", previous, deviceName));
        device.getErrors().put("Key", exception);
      } else {
        privateKeys.put(settings.credential, deviceName);
      }
    }
  }

  private Map<String, LocalDevice> loadDevices(File devicesDir, String[] devices, Pattern devicePattern) {
    HashMap<String, LocalDevice> localDevices = new HashMap<>();
    for (String deviceName : devices) {
      Matcher deviceMatch = devicePattern.matcher(deviceName);
      if (deviceMatch.find() && LocalDevice.deviceExists(devicesDir, deviceName)) {
        System.err.println("Loading local device " + deviceName);
        LocalDevice localDevice = new LocalDevice(devicesDir, deviceName, schemas);
        localDevices.put(deviceName, localDevice);
        try {
          localDevice.validateEnvelope(cloudIotManager.getRegistryId(), cloudIotManager.getSiteName());
        } catch (Exception e) {
          localDevice.getErrors().put("Envelope", e);
        }
      }
    }
    return localDevices;
  }

  private void setGcpCredPath(String gcpConfigPath) {
    this.gcpCredPath = gcpConfigPath;
  }

  private void setSchemaBase(String schemaBasePath) {
    schemaBase = new File(schemaBasePath);
    schemaName = schemaBase.getName();
    loadSchema(METADATA_JSON);
    loadSchema(ENVELOPE_JSON);
    loadSchema(PROPERTIES_JSON);
  }

  private void loadSchema(String key) {
    File schemaFile = new File(schemaBase, key);
    try (InputStream schemaStream = new FileInputStream(schemaFile)) {
      JSONObject rawSchema = new JSONObject(new JSONTokener(schemaStream));
      schemas.put(key, SchemaLoader.load(rawSchema, new Loader()));
    } catch (Exception e) {
      throw new RuntimeException("While loading schema " + schemaFile.getAbsolutePath(), e);
    }
  }

  private class Loader implements SchemaClient {

    public static final String FILE_PREFIX = "file:";

    @Override
    public InputStream get(String schema) {
      try {
        Preconditions.checkArgument(schema.startsWith(FILE_PREFIX));
        return new FileInputStream(new File(schemaBase, schema.substring(FILE_PREFIX.length())));
      } catch (Exception e) {
        throw new RuntimeException("While loading sub-schema " + schema, e);
      }
    }
  }
}

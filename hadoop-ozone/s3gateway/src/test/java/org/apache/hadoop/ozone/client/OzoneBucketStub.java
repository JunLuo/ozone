/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 *
 */
package org.apache.hadoop.ozone.client;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.stream.Collectors;

import org.apache.commons.codec.digest.DigestUtils;
import org.apache.hadoop.hdds.client.ReplicationConfig;
import org.apache.hadoop.hdds.client.ReplicationFactor;
import org.apache.hadoop.hdds.client.ReplicationType;
import org.apache.hadoop.hdds.client.StandaloneReplicationConfig;
import org.apache.hadoop.hdds.protocol.StorageType;
import org.apache.hadoop.hdds.protocol.proto.HddsProtos;
import org.apache.hadoop.ozone.OzoneAcl;
import org.apache.hadoop.ozone.client.io.OzoneInputStream;
import org.apache.hadoop.ozone.client.io.OzoneOutputStream;
import org.apache.hadoop.ozone.client.OzoneMultipartUploadPartListParts.PartInfo;
import org.apache.hadoop.ozone.om.exceptions.OMException;
import org.apache.hadoop.ozone.om.exceptions.OMException.ResultCodes;
import org.apache.hadoop.ozone.om.helpers.OmMultipartInfo;
import org.apache.hadoop.ozone.om.helpers.OmMultipartUploadCompleteInfo;
import org.apache.hadoop.util.Time;

/**
 * In-memory ozone bucket for testing.
 */
public class OzoneBucketStub extends OzoneBucket {

  private Map<String, OzoneKeyDetails> keyDetails = new HashMap<>();

  private Map<String, byte[]> keyContents = new HashMap<>();

  private Map<String, String> multipartUploadIdMap = new HashMap<>();

  private Map<String, Map<Integer, Part>> partList = new HashMap<>();

  private ArrayList<OzoneAcl> aclList = new ArrayList<>();

  /**
   * Constructs OzoneBucket instance.
   *
   * @param volumeName   Name of the volume the bucket belongs to.
   * @param bucketName   Name of the bucket.
   * @param storageType  StorageType of the bucket.
   * @param versioning   versioning status of the bucket.
   * @param creationTime creation time of the bucket.
   */
  public OzoneBucketStub(
      String volumeName,
      String bucketName,
      StorageType storageType, Boolean versioning,
      long creationTime) {
    super(volumeName,
        bucketName,
        StandaloneReplicationConfig
            .getInstance(HddsProtos.ReplicationFactor.ONE),
        storageType,
        versioning,
        creationTime);
  }

  @Override
  public OzoneOutputStream createKey(String key, long size) throws IOException {
    return createKey(key, size, ReplicationType.RATIS,
        ReplicationFactor.ONE, new HashMap<>());
  }

  @Override
  public OzoneOutputStream createKey(String key, long size,
                                     ReplicationType type,
                                     ReplicationFactor factor,
                                     Map<String, String> metadata)
      throws IOException {
    ByteArrayOutputStream byteArrayOutputStream =
        new ByteArrayOutputStream((int) size) {
          @Override
          public void close() throws IOException {
            keyContents.put(key, toByteArray());
            keyDetails.put(key, new OzoneKeyDetails(
                getVolumeName(),
                getName(),
                key,
                size,
                System.currentTimeMillis(),
                System.currentTimeMillis(),
                new ArrayList<>(), type, metadata, null,
                factor.getValue()
            ));
            super.close();
          }
        };
    return new OzoneOutputStream(byteArrayOutputStream);
  }

  @Override
  public OzoneInputStream readKey(String key) throws IOException {
    return new OzoneInputStream(new ByteArrayInputStream(keyContents.get(key)));
  }

  @Override
  public OzoneKeyDetails getKey(String key) throws IOException {
    if (keyDetails.containsKey(key)) {
      return keyDetails.get(key);
    } else {
      throw new OMException(ResultCodes.KEY_NOT_FOUND);
    }
  }

  @Override
  public OzoneKey headObject(String key) throws IOException {
    if (keyDetails.containsKey(key)) {
      OzoneKeyDetails ozoneKeyDetails = keyDetails.get(key);
      return new OzoneKey(ozoneKeyDetails.getVolumeName(),
          ozoneKeyDetails.getBucketName(),
          ozoneKeyDetails.getName(),
          ozoneKeyDetails.getDataSize(),
          ozoneKeyDetails.getCreationTime().toEpochMilli(),
          ozoneKeyDetails.getModificationTime().toEpochMilli(),
          ReplicationConfig.fromTypeAndFactor(
              ozoneKeyDetails.getReplicationType(),
              ReplicationFactor.valueOf(ozoneKeyDetails.getReplicationFactor())
          ));
    } else {
      throw new OMException(ResultCodes.KEY_NOT_FOUND);
    }
  }

  @Override
  public Iterator<? extends OzoneKey> listKeys(String keyPrefix) {
    Map<String, OzoneKey> sortedKey = new TreeMap<String, OzoneKey>(keyDetails);
    return sortedKey.values()
        .stream()
        .filter(key -> key.getName().startsWith(keyPrefix))
        .collect(Collectors.toList())
        .iterator();
  }

  @Override
  public Iterator<? extends OzoneKey> listKeys(String keyPrefix,
      String prevKey) {
    Map<String, OzoneKey> sortedKey = new TreeMap<String, OzoneKey>(keyDetails);
    return sortedKey.values()
        .stream()
        .filter(key -> key.getName().compareTo(prevKey) > 0)
        .filter(key -> key.getName().startsWith(keyPrefix))
        .collect(Collectors.toList())
        .iterator();
  }

  @Override
  public void deleteKey(String key) throws IOException {
    keyDetails.remove(key);
  }

  @Override
  public void renameKey(String fromKeyName, String toKeyName)
      throws IOException {
    throw new UnsupportedOperationException();
  }

  @Override
  public OmMultipartInfo initiateMultipartUpload(String keyName,
                                                 ReplicationType type,
                                                 ReplicationFactor factor)
      throws IOException {
    String uploadID = UUID.randomUUID().toString();
    multipartUploadIdMap.put(keyName, uploadID);
    return new OmMultipartInfo(getVolumeName(), getName(), keyName, uploadID);
  }

  @Override
  public OzoneOutputStream createMultipartKey(String key, long size,
                                              int partNumber, String uploadID)
      throws IOException {
    String multipartUploadID = multipartUploadIdMap.get(key);
    if (multipartUploadID == null || !multipartUploadID.equals(uploadID)) {
      throw new OMException(ResultCodes.NO_SUCH_MULTIPART_UPLOAD_ERROR);
    } else {
      ByteArrayOutputStream byteArrayOutputStream =
          new ByteArrayOutputStream((int) size) {
            @Override
            public void close() throws IOException {
              Part part = new Part(key + size,
                  toByteArray());
              if (partList.get(key) == null) {
                Map<Integer, Part> parts = new TreeMap<>();
                parts.put(partNumber, part);
                partList.put(key, parts);
              } else {
                partList.get(key).put(partNumber, part);
              }
            }
          };
      return new OzoneOutputStreamStub(byteArrayOutputStream, key + size);
    }
  }

  @Override
  public OmMultipartUploadCompleteInfo completeMultipartUpload(String key,
      String uploadID, Map<Integer, String> partsMap) throws IOException {

    if (multipartUploadIdMap.get(key) == null) {
      throw new OMException(ResultCodes.NO_SUCH_MULTIPART_UPLOAD_ERROR);
    } else {
      final Map<Integer, Part> partsList = partList.get(key);

      int count = 1;

      ByteArrayOutputStream output = new ByteArrayOutputStream();

      int prevPartNumber = 0;
      for (Map.Entry<Integer, String> part: partsMap.entrySet()) {
        int currentPartNumber = part.getKey();
        if (currentPartNumber <= prevPartNumber) {
          throw new OMException(OMException.ResultCodes.INVALID_PART_ORDER);
        }
        prevPartNumber = currentPartNumber;
      }
      for (Map.Entry<Integer, String> part: partsMap.entrySet()) {
        Part recordedPart = partsList.get(part.getKey());
        if (recordedPart == null ||
            !recordedPart.getPartName().equals(part.getValue())) {
          throw new OMException(ResultCodes.INVALID_PART);
        } else {
          output.write(recordedPart.getContent());
        }
        keyContents.put(key, output.toByteArray());
      }
    }

    return new OmMultipartUploadCompleteInfo(getVolumeName(), getName(), key,
        DigestUtils.sha256Hex(key));
  }

  @Override
  public void abortMultipartUpload(String keyName, String uploadID) throws
      IOException {
    if (multipartUploadIdMap.get(keyName) == null) {
      throw new OMException(ResultCodes.NO_SUCH_MULTIPART_UPLOAD_ERROR);
    } else {
      multipartUploadIdMap.remove(keyName);
    }
  }

  @Override
  public OzoneMultipartUploadPartListParts listParts(String key,
      String uploadID, int partNumberMarker, int maxParts) throws IOException {
    if (multipartUploadIdMap.get(key) == null) {
      throw new OMException(ResultCodes.NO_SUCH_MULTIPART_UPLOAD_ERROR);
    }
    List<PartInfo> partInfoList = new ArrayList<>();

    if (partList.get(key) == null) {
      return new OzoneMultipartUploadPartListParts(ReplicationType.RATIS,
          ReplicationFactor.ONE, 0, false);
    } else {
      Map<Integer, Part> partMap = partList.get(key);
      Iterator<Map.Entry<Integer, Part>> partIterator =
          partMap.entrySet().iterator();

      int count = 0;
      int nextPartNumberMarker = 0;
      boolean truncated = false;
      while (count < maxParts && partIterator.hasNext()) {
        Map.Entry<Integer, Part> partEntry = partIterator.next();
        nextPartNumberMarker = partEntry.getKey();
        if (partEntry.getKey() > partNumberMarker) {
          PartInfo partInfo = new PartInfo(partEntry.getKey(),
              partEntry.getValue().getPartName(),
              partEntry.getValue().getContent().length, Time.now());
          partInfoList.add(partInfo);
          count++;
        }
      }

      if (partIterator.hasNext()) {
        truncated = true;
      } else {
        truncated = false;
        nextPartNumberMarker = 0;
      }

      OzoneMultipartUploadPartListParts ozoneMultipartUploadPartListParts =
          new OzoneMultipartUploadPartListParts(ReplicationType.RATIS,
              ReplicationFactor.ONE,
              nextPartNumberMarker, truncated);
      ozoneMultipartUploadPartListParts.addAllParts(partInfoList);

      return ozoneMultipartUploadPartListParts;

    }
  }

  @Override
  public List<OzoneAcl> getAcls() throws IOException {
    return (List<OzoneAcl>)aclList.clone();
  }

  @Override
  public boolean removeAcl(OzoneAcl removeAcl) throws IOException {
    return aclList.remove(removeAcl);
  }

  @Override
  public boolean addAcl(OzoneAcl addAcl) throws IOException {
    return aclList.add(addAcl);
  }

  @Override
  public boolean setAcl(List<OzoneAcl> acls) throws IOException {
    aclList.clear();
    return aclList.addAll(acls);
  }

  /**
   * Class used to hold part information in a upload part request.
   */
  public static class Part {
    private String partName;
    private byte[] content;

    public Part(String name, byte[] data) {
      this.partName = name;
      this.content = data.clone();
    }

    public String getPartName() {
      return partName;
    }

    public byte[] getContent() {
      return content.clone();
    }
  }
}

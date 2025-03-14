/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.hadoop.ozone.client;

import java.io.IOException;
import java.time.Instant;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

import org.apache.commons.collections.ListUtils;
import org.apache.hadoop.hdds.client.OzoneQuota;
import org.apache.hadoop.hdds.conf.ConfigurationSource;
import org.apache.hadoop.hdds.scm.client.HddsClientUtils;
import org.apache.hadoop.ozone.OzoneAcl;
import org.apache.hadoop.ozone.client.protocol.ClientProtocol;
import org.apache.hadoop.ozone.om.helpers.WithMetadata;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import org.apache.hadoop.ozone.security.acl.OzoneObj;
import org.apache.hadoop.ozone.security.acl.OzoneObjInfo;

import static org.apache.hadoop.ozone.OzoneConsts.QUOTA_RESET;

/**
 * A class that encapsulates OzoneVolume.
 */
public class OzoneVolume extends WithMetadata {

  /**
   * The proxy used for connecting to the cluster and perform
   * client operations.
   */
  private final ClientProtocol proxy;

  /**
   * Name of the Volume.
   */
  private final String name;

  /**
   * Admin Name of the Volume.
   */
  private String admin;
  /**
   * Owner of the Volume.
   */
  private String owner;
  /**
   * Quota of bytes allocated for the Volume.
   */
  private long quotaInBytes;
  /**
   * Quota of bucket count allocated for the Volume.
   */
  private long quotaInNamespace;
  /**
   * Bucket namespace quota usage.
   */
  private long usedNamespace;
  /**
   * Creation time of the volume.
   */
  private Instant creationTime;
  /**
   * Modification time of the volume.
   */
  private Instant modificationTime;
  /**
   * Volume ACLs.
   */
  private List<OzoneAcl> acls;

  private int listCacheSize;

  private OzoneObj ozoneObj;

  /**
   * Constructs OzoneVolume instance.
   * @param conf Configuration object.
   * @param proxy ClientProtocol proxy.
   * @param name Name of the volume.
   * @param admin Volume admin.
   * @param owner Volume owner.
   * @param quotaInBytes Volume quota in bytes.
   * @param creationTime creation time of the volume
   * @param acls ACLs associated with the volume.
   * @param metadata custom key value metadata.
   */
  @SuppressWarnings("parameternumber")
  public OzoneVolume(ConfigurationSource conf, ClientProtocol proxy,
      String name, String admin, String owner, long quotaInBytes,
      long quotaInNamespace, long creationTime, List<OzoneAcl> acls,
      Map<String, String> metadata) {
    Preconditions.checkNotNull(proxy, "Client proxy is not set.");
    this.proxy = proxy;
    this.name = name;
    this.admin = admin;
    this.owner = owner;
    this.quotaInBytes = quotaInBytes;
    this.quotaInNamespace = quotaInNamespace;
    this.creationTime = Instant.ofEpochMilli(creationTime);
    this.acls = acls;
    this.listCacheSize = HddsClientUtils.getListCacheSize(conf);
    this.metadata = metadata;
    modificationTime = Instant.now();
    if (modificationTime.isBefore(this.creationTime)) {
      modificationTime = Instant.ofEpochSecond(
          this.creationTime.getEpochSecond(), this.creationTime.getNano());
    }
    this.ozoneObj = OzoneObjInfo.Builder.newBuilder()
        .setVolumeName(name)
        .setResType(OzoneObj.ResourceType.VOLUME)
        .setStoreType(OzoneObj.StoreType.OZONE).build();
  }

  /**
   * @param modificationTime modification time of the volume.
   */
  @SuppressWarnings("parameternumber")
  public OzoneVolume(ConfigurationSource conf, ClientProtocol proxy,
      String name, String admin, String owner, long quotaInBytes,
      long quotaInNamespace, long usedNamespace, long creationTime,
      long modificationTime, List<OzoneAcl> acls,
      Map<String, String> metadata) {
    this(conf, proxy, name, admin, owner, quotaInBytes, quotaInNamespace,
        creationTime, acls, metadata);
    this.modificationTime = Instant.ofEpochMilli(modificationTime);
    this.usedNamespace = usedNamespace;
  }

  @SuppressWarnings("parameternumber")
  public OzoneVolume(ConfigurationSource conf, ClientProtocol proxy,
      String name, String admin, String owner, long quotaInBytes,
      long quotaInNamespace, long creationTime, List<OzoneAcl> acls) {
    this(conf, proxy, name, admin, owner, quotaInBytes, quotaInNamespace,
        creationTime, acls, new HashMap<>());
    modificationTime = Instant.now();
    if (modificationTime.isBefore(this.creationTime)) {
      modificationTime = Instant.ofEpochSecond(
          this.creationTime.getEpochSecond(), this.creationTime.getNano());
    }
  }

  @SuppressWarnings("parameternumber")
  public OzoneVolume(ConfigurationSource conf, ClientProtocol proxy,
      String name, String admin, String owner, long quotaInBytes,
      long quotaInNamespace, long usedNamespace, long creationTime,
      long modificationTime, List<OzoneAcl> acls) {
    this(conf, proxy, name, admin, owner, quotaInBytes, quotaInNamespace,
        creationTime, acls);
    this.modificationTime = Instant.ofEpochMilli(modificationTime);
    this.usedNamespace = usedNamespace;
  }

  @VisibleForTesting
  protected OzoneVolume(String name, String admin, String owner,
      long quotaInBytes, long quotaInNamespace, long creationTime,
      List<OzoneAcl> acls) {
    this.proxy = null;
    this.name = name;
    this.admin = admin;
    this.owner = owner;
    this.quotaInBytes = quotaInBytes;
    this.quotaInNamespace = quotaInNamespace;
    this.creationTime = Instant.ofEpochMilli(creationTime);
    this.acls = acls;
    this.metadata = new HashMap<>();
    modificationTime = Instant.now();
    if (modificationTime.isBefore(this.creationTime)) {
      modificationTime = Instant.ofEpochSecond(
          this.creationTime.getEpochSecond(), this.creationTime.getNano());
    }
  }

  @SuppressWarnings("parameternumber")
  @VisibleForTesting
  protected OzoneVolume(String name, String admin, String owner,
      long quotaInBytes, long quotaInNamespace, long creationTime,
      long modificationTime, List<OzoneAcl> acls) {
    this(name, admin, owner, quotaInBytes, quotaInNamespace, creationTime,
        acls);
    this.modificationTime = Instant.ofEpochMilli(modificationTime);
  }

  /**
   * Returns Volume name.
   *
   * @return volumeName
   */
  public String getName() {
    return name;
  }

  /**
   * Returns Volume's admin name.
   *
   * @return adminName
   */
  public String getAdmin() {
    return admin;
  }

  /**
   * Returns Volume's owner name.
   *
   * @return ownerName
   */
  public String getOwner() {
    return owner;
  }

  /**
   * Returns Quota allocated for the Volume in bytes.
   *
   * @return quotaInBytes
   */
  public long getQuotaInBytes() {
    return quotaInBytes;
  }

  /**
   * Returns quota of bucket counts allocated for the Volume.
   *
   * @return quotaInNamespace
   */
  public long getQuotaInNamespace() {
    return quotaInNamespace;
  }
  /**
   * Returns creation time of the volume.
   *
   * @return creation time.
   */
  public Instant getCreationTime() {
    return creationTime;
  }

  /**
   * Returns modification time of the volume.
   *
   * @return modification time.
   */
  public Instant getModificationTime() {
    return modificationTime;
  }

  /**
   * Returns OzoneAcl list associated with the Volume.
   *
   * @return aclMap
   */
  public List<OzoneAcl> getAcls() {
    return ListUtils.unmodifiableList(acls);
  }

   /**
   * Adds ACLs to the volume.
   * @param addAcl ACL to be added
   * @return true - if acl is successfully added, false if acl already exists
   * for the bucket.
   * @throws IOException
   */
  public boolean addAcl(OzoneAcl addAcl) throws IOException {
    boolean added = proxy.addAcl(ozoneObj, addAcl);
    if (added) {
      acls.add(addAcl);
    }
    return added;
  }

  /**
   * Remove acl for Ozone object. Return true if acl is removed successfully
   * else false.
   * @param acl Ozone acl to be removed.
   *
   * @throws IOException if there is error.
   * */
  public boolean removeAcl(OzoneAcl acl) throws IOException {
    boolean removed = proxy.removeAcl(ozoneObj, acl);
    if (removed) {
      acls.remove(acl);
    }
    return removed;
  }

  /**
   * Acls to be set for given Ozone object. This operations reset ACL for
   * given object to list of ACLs provided in argument.
   * @param aclList List of acls.
   *
   * @throws IOException if there is error.
   * */
  public boolean setAcl(List<OzoneAcl> aclList) throws IOException {
    boolean reset = proxy.setAcl(ozoneObj, aclList);
    if (reset) {
      acls.clear();
      acls.addAll(aclList);
    }
    return reset;
  }

  /**
   * Returns used bucket namespace.
   * @return usedNamespace
   */
  public long getUsedNamespace() {
    return usedNamespace;
  }

  /**
   * Sets/Changes the owner of this Volume.
   * @param userName new owner
   * @throws IOException
   */
  public boolean setOwner(String userName) throws IOException {
    boolean result = proxy.setVolumeOwner(name, userName);
    this.owner = userName;
    return result;
  }

  /**
   * Clean the space quota of the volume.
   *
   * @throws IOException
   */
  public void clearSpaceQuota() throws IOException {
    OzoneVolume ozoneVolume = proxy.getVolumeDetails(name);
    proxy.setVolumeQuota(name, ozoneVolume.getQuotaInNamespace(), QUOTA_RESET);
    this.quotaInBytes = QUOTA_RESET;
    this.quotaInNamespace = ozoneVolume.getQuotaInNamespace();
  }

  /**
   * Clean the namespace quota of the volume.
   *
   * @throws IOException
   */
  public void clearNamespaceQuota() throws IOException {
    OzoneVolume ozoneVolume = proxy.getVolumeDetails(name);
    proxy.setVolumeQuota(name, QUOTA_RESET, ozoneVolume.getQuotaInBytes());
    this.quotaInBytes = ozoneVolume.getQuotaInBytes();
    this.quotaInNamespace = QUOTA_RESET;
  }

  /**
   * Sets/Changes the quota of this Volume.
   *
   * @param quota OzoneQuota Object that can be applied to storage volume.
   * @throws IOException
   */
  public void setQuota(OzoneQuota quota) throws IOException {
    proxy.setVolumeQuota(name, quota.getQuotaInNamespace(),
        quota.getQuotaInBytes());
    this.quotaInBytes = quota.getQuotaInBytes();
    this.quotaInNamespace = quota.getQuotaInNamespace();
  }

  /**
   * Creates a new Bucket in this Volume, with default values.
   * @param bucketName Name of the Bucket
   * @throws IOException
   */
  public void createBucket(String bucketName)
      throws IOException {
    proxy.createBucket(name, bucketName);
  }

  /**
   * Creates a new Bucket in this Volume, with properties set in bucketArgs.
   * @param bucketName Name of the Bucket
   * @param bucketArgs Properties to be set
   * @throws IOException
   */
  public void createBucket(String bucketName, BucketArgs bucketArgs)
      throws IOException {
    proxy.createBucket(name, bucketName, bucketArgs);
  }

  /**
   * Get the Bucket from this Volume.
   * @param bucketName Name of the Bucket
   * @return OzoneBucket
   * @throws IOException
   */
  public OzoneBucket getBucket(String bucketName) throws IOException {
    OzoneBucket bucket = proxy.getBucketDetails(name, bucketName);
    return bucket;
  }

  /**
   * Returns Iterator to iterate over all buckets in the volume.
   * The result can be restricted using bucket prefix, will return all
   * buckets if bucket prefix is null.
   *
   * @param bucketPrefix Bucket prefix to match
   * @return {@code Iterator<OzoneBucket>}
   */
  public Iterator<? extends OzoneBucket> listBuckets(String bucketPrefix) {
    return listBuckets(bucketPrefix, null);
  }

  /**
   * Returns Iterator to iterate over all buckets after prevBucket in the
   * volume.
   * If prevBucket is null it iterates from the first bucket in the volume.
   * The result can be restricted using bucket prefix, will return all
   * buckets if bucket prefix is null.
   *
   * @param bucketPrefix Bucket prefix to match
   * @param prevBucket Buckets are listed after this bucket
   * @return {@code Iterator<OzoneBucket>}
   */
  public Iterator<? extends OzoneBucket> listBuckets(String bucketPrefix,
      String prevBucket) {
    return new BucketIterator(bucketPrefix, prevBucket);
  }

  /**
   * Deletes the Bucket from this Volume.
   * @param bucketName Name of the Bucket
   * @throws IOException
   */
  public void deleteBucket(String bucketName) throws IOException {
    proxy.deleteBucket(name, bucketName);
  }


  /**
   * An Iterator to iterate over {@link OzoneBucket} list.
   */
  private class BucketIterator implements Iterator<OzoneBucket> {

    private String bucketPrefix = null;

    private Iterator<OzoneBucket> currentIterator;
    private OzoneBucket currentValue;


    /**
     * Creates an Iterator to iterate over all buckets after prevBucket in
     * the volume.
     * If prevBucket is null it iterates from the first bucket in the volume.
     * The returned buckets match bucket prefix.
     * @param bucketPrefix
     */
    BucketIterator(String bucketPrefix, String prevBucket) {
      this.bucketPrefix = bucketPrefix;
      this.currentValue = null;
      this.currentIterator = getNextListOfBuckets(prevBucket).iterator();
    }

    @Override
    public boolean hasNext() {
      if (!currentIterator.hasNext() && currentValue != null) {
        currentIterator = getNextListOfBuckets(currentValue.getName())
            .iterator();
      }
      return currentIterator.hasNext();
    }

    @Override
    public OzoneBucket next() {
      if (hasNext()) {
        currentValue = currentIterator.next();
        return currentValue;
      }
      throw new NoSuchElementException();
    }

    /**
     * Gets the next set of bucket list using proxy.
     * @param prevBucket
     * @return {@code List<OzoneBucket>}
     */
    private List<OzoneBucket> getNextListOfBuckets(String prevBucket) {
      try {
        return proxy.listBuckets(name, bucketPrefix, prevBucket, listCacheSize);
      } catch (IOException e) {
        throw new RuntimeException(e);
      }
    }
  }
}
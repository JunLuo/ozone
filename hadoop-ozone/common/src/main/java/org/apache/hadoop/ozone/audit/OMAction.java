/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with this
 * work for additional information regarding copyright ownership.  The ASF
 * licenses this file to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package org.apache.hadoop.ozone.audit;

/**
 * Enum to define Audit Action types for OzoneManager.
 */
public enum OMAction implements AuditAction {

  // WRITE Actions
  ALLOCATE_BLOCK,
  ALLOCATE_KEY,
  COMMIT_KEY,
  CREATE_VOLUME,
  CREATE_BUCKET,
  DELETE_VOLUME,
  DELETE_BUCKET,
  DELETE_KEY,
  RENAME_KEY,
  RENAME_KEYS,
  SET_OWNER,
  SET_QUOTA,
  UPDATE_VOLUME,
  UPDATE_BUCKET,
  UPDATE_KEY,
  PURGE_KEYS,
  DELETE_KEYS,

  // READ Actions
  CHECK_VOLUME_ACCESS,
  LIST_BUCKETS,
  LIST_VOLUMES,
  LIST_KEYS,
  LIST_TRASH,
  READ_VOLUME,
  READ_BUCKET,
  READ_KEY,
  INITIATE_MULTIPART_UPLOAD,
  COMMIT_MULTIPART_UPLOAD_PARTKEY,
  COMPLETE_MULTIPART_UPLOAD,
  LIST_MULTIPART_UPLOAD_PARTS,
  LIST_MULTIPART_UPLOADS,
  ABORT_MULTIPART_UPLOAD,
  GET_DELEGATION_TOKEN,
  RENEW_DELEGATION_TOKEN,
  CANCEL_DELEGATION_TOKEN,
  GET_SERVICE_LIST,

  //ACL Actions
  ADD_ACL,
  GET_ACL,
  SET_ACL,
  REMOVE_ACL,

  //FS Actions
  GET_FILE_STATUS,
  CREATE_DIRECTORY,
  CREATE_FILE,
  LOOKUP_FILE,
  LIST_STATUS,

  GET_S3_SECRET,
  REVOKE_S3_SECRET;

  @Override
  public String getAction() {
    return this.toString();
  }

}

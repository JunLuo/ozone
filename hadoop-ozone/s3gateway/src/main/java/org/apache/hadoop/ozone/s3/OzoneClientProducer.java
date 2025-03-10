/*
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
package org.apache.hadoop.ozone.s3;

import javax.annotation.PreDestroy;
import javax.enterprise.context.RequestScoped;
import javax.enterprise.inject.Produces;
import javax.inject.Inject;
import javax.ws.rs.WebApplicationException;
import javax.ws.rs.container.ContainerRequestContext;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.Response;
import java.io.IOException;

import com.google.common.annotations.VisibleForTesting;
import org.apache.hadoop.hdds.conf.OzoneConfiguration;
import org.apache.hadoop.ozone.OmUtils;
import org.apache.hadoop.ozone.client.OzoneClient;
import org.apache.hadoop.ozone.client.OzoneClientFactory;
import org.apache.hadoop.ozone.om.protocol.S3Auth;
import org.apache.hadoop.ozone.s3.exception.OS3Exception;
import org.apache.hadoop.ozone.s3.exception.S3ErrorTable;
import org.apache.hadoop.ozone.s3.signature.SignatureInfo;
import org.apache.hadoop.ozone.s3.signature.SignatureInfo.Version;
import org.apache.hadoop.ozone.s3.signature.SignatureProcessor;
import org.apache.hadoop.ozone.s3.signature.StringToSignProducer;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.apache.hadoop.ozone.OzoneConfigKeys.OZONE_CLIENT_REQUIRED_OM_VERSION_MIN_DEFAULT;
import static org.apache.hadoop.ozone.OzoneConfigKeys.OZONE_CLIENT_REQUIRED_OM_VERSION_MIN_KEY;
import static org.apache.hadoop.ozone.s3.exception.S3ErrorTable.ACCESS_DENIED;
import static org.apache.hadoop.ozone.s3.exception.S3ErrorTable.INTERNAL_ERROR;

/**
 * This class creates the OzoneClient for the Rest endpoints.
 */
@RequestScoped
public class OzoneClientProducer {

  private static final Logger LOG =
      LoggerFactory.getLogger(OzoneClientProducer.class);

  private static OzoneClient client;

  @Inject
  private SignatureProcessor signatureProcessor;

  @Inject
  private OzoneConfiguration ozoneConfiguration;

  @Context
  private ContainerRequestContext context;

  @Produces
  public synchronized OzoneClient createClient() throws WebApplicationException,
      IOException {
    if (client == null) {
      client = createOzoneClient();
    }
    return client;
  }

  @PreDestroy
  public void destroy() throws IOException {
    client.getObjectStore().getClientProxy().clearThreadLocalS3Auth();
  }
  @Produces
  public S3Auth getSignature() {
    try {
      SignatureInfo signatureInfo = signatureProcessor.parseSignature();
      String stringToSign = "";
      if (signatureInfo.getVersion() == Version.V4) {
        stringToSign =
            StringToSignProducer.createSignatureBase(signatureInfo, context);
      }

      String awsAccessId = signatureInfo.getAwsAccessId();
      // ONLY validate aws access id when needed.
      if (awsAccessId == null || awsAccessId.equals("")) {
        LOG.debug("Malformed s3 header. awsAccessID: {}", awsAccessId);
        throw ACCESS_DENIED;
      }

      return new S3Auth(stringToSign,
          signatureInfo.getSignature(),
          awsAccessId);
    } catch (OS3Exception ex) {
      LOG.debug("Error during Client Creation: ", ex);
      throw wrapOS3Exception(ex);
    } catch (Exception e) {
      // For any other critical errors during object creation throw Internal
      // error.
      LOG.debug("Error during Client Creation: ", e);
      throw wrapOS3Exception(S3ErrorTable.newError(INTERNAL_ERROR, null, e));
    }
  }

  @NotNull
  @VisibleForTesting
  OzoneClient createOzoneClient() throws IOException {
    // S3 Gateway should always set the S3 Auth.
    ozoneConfiguration.setBoolean(S3Auth.S3_AUTH_CHECK, true);
    // Set the expected OM version if not set via config.
    ozoneConfiguration.setIfUnset(OZONE_CLIENT_REQUIRED_OM_VERSION_MIN_KEY,
        OZONE_CLIENT_REQUIRED_OM_VERSION_MIN_DEFAULT);
    String omServiceID = OmUtils.getOzoneManagerServiceId(ozoneConfiguration);
    if (omServiceID == null) {
      return OzoneClientFactory.getRpcClient(ozoneConfiguration);
    } else {
      // As in HA case, we need to pass om service ID.
      return OzoneClientFactory.getRpcClient(omServiceID,
          ozoneConfiguration);
    }
  }

  public void setOzoneConfiguration(OzoneConfiguration config) {
    this.ozoneConfiguration = config;
  }

  @VisibleForTesting
  public void setSignatureParser(SignatureProcessor awsSignatureProcessor) {
    this.signatureProcessor = awsSignatureProcessor;
  }

  private WebApplicationException wrapOS3Exception(OS3Exception os3Exception) {
    return new WebApplicationException(os3Exception.getErrorMessage(),
        os3Exception,
        Response.status(os3Exception.getHttpCode())
            .entity(os3Exception.toXml()).build());
  }
}

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
 *
 * Modified by Datazip Inc. in 2026
 */

package org.apache.amoro.aws;

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;

import java.util.Map;

/**
 * A static AWS credentials provider that can be instantiated by Iceberg's {@link
 * org.apache.iceberg.aws.AwsClientProperties} via the {@code create(Map)} reflection mechanism.
 *
 * <p>When configured as {@code client.credentials-provider}, Iceberg strips the {@code
 * client.credentials-provider.} prefix from sub-properties and passes the remaining map to {@link
 * #create(Map)}. This provider reads the following keys from that map:
 *
 * <ul>
 *   <li>{@code access-key-id} — AWS access key ID
 *   <li>{@code secret-access-key} — AWS secret access key
 * </ul>
 *
 * <p>This is needed because the Iceberg Glue client does not read S3-specific credential properties
 * ({@code s3.access-key-id} / {@code s3.secret-access-key}). Without this provider, the Glue client
 * falls back to the default AWS credential chain, which may not have valid credentials.
 */
public class StaticAwsCredentialsProvider implements AwsCredentialsProvider {

  public static final String ACCESS_KEY_ID = "access-key-id";
  public static final String SECRET_ACCESS_KEY = "secret-access-key";

  private final AwsCredentials credentials;

  private StaticAwsCredentialsProvider(String accessKeyId, String secretAccessKey) {
    this.credentials = AwsBasicCredentials.create(accessKeyId, secretAccessKey);
  }

  /**
   * Called by Iceberg via reflection when {@code client.credentials-provider} is set to this
   * class's fully qualified name. The properties map contains sub-properties under the {@code
   * client.credentials-provider.} prefix (with the prefix stripped).
   *
   * @param properties map containing {@code access-key-id} and {@code secret-access-key}
   * @return an {@link AwsCredentialsProvider} using the provided static credentials
   * @throws IllegalArgumentException if either key is missing
   */
  public static AwsCredentialsProvider create(Map<String, String> properties) {
    String accessKeyId = properties.get(ACCESS_KEY_ID);
    String secretAccessKey = properties.get(SECRET_ACCESS_KEY);
    if (accessKeyId == null || secretAccessKey == null) {
      throw new IllegalArgumentException(
          "Both 'access-key-id' and 'secret-access-key' must be provided in "
              + "client.credentials-provider properties");
    }
    return new StaticAwsCredentialsProvider(accessKeyId, secretAccessKey);
  }

  @Override
  public AwsCredentials resolveCredentials() {
    return credentials;
  }
}

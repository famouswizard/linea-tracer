/*
 * Copyright Consensys Software Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on
 * an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations under the License.
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package net.consensys.linea.plugins.rpc.linecounts;

import com.google.auto.service.AutoService;
import net.consensys.linea.plugins.AbstractLineaPrivateOptionsPlugin;
import net.consensys.linea.plugins.BesuServiceProvider;
import net.consensys.linea.plugins.rpc.RequestLimiter;
import net.consensys.linea.plugins.rpc.RequestLimiterDispatcher;
import org.hyperledger.besu.plugin.BesuContext;
import org.hyperledger.besu.plugin.BesuPlugin;
import org.hyperledger.besu.plugin.services.RpcEndpointService;

/**
 * Sets up an RPC endpoint for generating trace counters.
 *
 * <p>The CountersEndpointServicePlugin registers an RPC endpoint named
 * 'getTracesCountersByBlockNumberV0' under the 'rollup' namespace. When this endpoint is called,
 * returns trace counters based on the provided request parameters. See {@link GenerateLineCountsV2}
 */
@AutoService(BesuPlugin.class)
public class LineCountsEndpointServicePlugin extends AbstractLineaPrivateOptionsPlugin {
  private BesuContext besuContext;
  private RpcEndpointService rpcEndpointService;

  /**
   * Register the RPC service.
   *
   * @param context the BesuContext to be used.
   */
  @Override
  public void register(final BesuContext context) {
    super.register(context);
    besuContext = context;
    rpcEndpointService = BesuServiceProvider.getRpcEndpointService(context);
  }

  @Override
  public void beforeExternalServices() {
    super.beforeExternalServices();

    RequestLimiterDispatcher.setLimiterIfMissing(
        RequestLimiterDispatcher.SINGLE_INSTANCE_REQUEST_LIMITER_KEY,
        rpcConfiguration().concurrentRequestsLimit());
    final RequestLimiter reqLimiter =
        RequestLimiterDispatcher.getLimiter(
            RequestLimiterDispatcher.SINGLE_INSTANCE_REQUEST_LIMITER_KEY);

    final GenerateLineCountsV2 method = new GenerateLineCountsV2(besuContext, reqLimiter);
    createAndRegister(method, rpcEndpointService);
  }

  /**
   * Create and register the RPC service.
   *
   * @param method the RollupGenerateCountersV0 method to be used.
   * @param rpcEndpointService the RpcEndpointService to be registered.
   */
  private void createAndRegister(
      final GenerateLineCountsV2 method, final RpcEndpointService rpcEndpointService) {
    rpcEndpointService.registerRPCEndpoint(
        method.getNamespace(), method.getName(), method::execute);
  }

  /** Start the RPC service. This method loads the OpCodes. */
  @Override
  public void start() {}
}

/**
 * Copyright 2017 StreamSets Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.streamsets.datacollector.execution.alerts;

import com.streamsets.datacollector.config.AuthenticationType;
import com.streamsets.datacollector.config.PipelineWebhookConfig;
import com.streamsets.datacollector.config.WebhookCommonConfig;
import com.streamsets.datacollector.creation.PipelineConfigBean;
import com.streamsets.datacollector.execution.PipelineState;
import com.streamsets.datacollector.execution.StateEventListener;
import com.streamsets.datacollector.main.RuntimeInfo;
import com.streamsets.datacollector.runner.PipelineRuntimeException;
import com.streamsets.dc.execution.manager.standalone.ThreadUsage;
import com.streamsets.pipeline.api.ExecutionMode;
import org.apache.commons.lang3.StringUtils;
import org.glassfish.jersey.client.authentication.HttpAuthenticationFeature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.ws.rs.client.ClientBuilder;
import javax.ws.rs.client.Entity;
import javax.ws.rs.client.Invocation;
import javax.ws.rs.client.WebTarget;
import javax.ws.rs.core.Response;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public class WebHookNotifier implements StateEventListener {

  private static final Logger LOG = LoggerFactory.getLogger(WebHookNotifier.class);
  private final String pipelineId;
  private final String pipelineTitle;
  private final String rev;
  private final PipelineConfigBean pipelineConfigBean;
  private final RuntimeInfo runtimeInfo;
  private Set<String> pipelineStates;

  public WebHookNotifier(
      String pipelineId,
      String pipelineTitle,
      String rev,
      PipelineConfigBean pipelineConfigBean,
      RuntimeInfo runtimeInfo
  ) {
    this.pipelineId = pipelineId;
    this.pipelineTitle = pipelineTitle;
    this.rev = rev;
    this.pipelineConfigBean = pipelineConfigBean;
    this.runtimeInfo = runtimeInfo;

    pipelineStates = new HashSet<>();
    for(com.streamsets.datacollector.config.PipelineState s : pipelineConfigBean.notifyOnStates) {
      pipelineStates.add(s.name());
    }
  }

  public String getPipelineId() {
    return pipelineId;
  }

  public String getRev() {
    return rev;
  }

  @Override
  public void onStateChange(
      PipelineState fromState,
      PipelineState toState,
      String toStateJson,
      ThreadUsage threadUsage,
      Map<String, String> offset
  ) throws PipelineRuntimeException {
    //should not be active in slave mode
    if(toState.getExecutionMode() != ExecutionMode.SLAVE && pipelineId.equals(toState.getPipelineId())) {
      if (pipelineStates != null && pipelineStates.contains(toState.getStatus().name())) {
        for (PipelineWebhookConfig webhookConfig : pipelineConfigBean.webhookConfigs) {
          if (!StringUtils.isEmpty(webhookConfig.webhookUrl)) {
            Response response = null;
            try {
              DateFormat dateTimeFormat = new SimpleDateFormat(EmailConstants.DATE_MASK, Locale.ENGLISH);
              String payload = webhookConfig.payload
                  .replace(WebhookConstants.PIPELINE_TITLE_KEY, pipelineTitle)
                  .replace(WebhookConstants.PIPELINE_URL_KEY, runtimeInfo.getBaseHttpUrl() +
                      EmailConstants.PIPELINE_URL + toState.getPipelineId().replaceAll(" ", "%20"))
                  .replace(WebhookConstants.PIPELINE_STATE_KEY, toState.getStatus().toString())
                  .replace(WebhookConstants.TIME_KEY, dateTimeFormat.format(new Date(toState.getTimeStamp())));

              WebTarget webTarget = ClientBuilder.newClient().target(webhookConfig.webhookUrl);
              configurePasswordAuth(webhookConfig, webTarget);
              Invocation.Builder builder = webTarget.request();
              for (String headerKey: webhookConfig.headers.keySet()) {
                builder.header(headerKey, webhookConfig.headers.get(headerKey));
              }
              response = builder.post(Entity.entity(payload, webhookConfig.contentType));

              if (response.getStatus() != Response.Status.OK.getStatusCode()) {
                LOG.error(
                    "Error calling Webhook URL, status code '{}': {}",
                    response.getStatus(),
                    response.readEntity(String.class)
                );
              }
            } catch (Exception e) {
              LOG.error("Error calling Webhook URL : {}", e.toString(), e);
            } finally {
              if (response != null) {
                response.close();
              }
            }
          }
        }
      }
    }
  }

  private void configurePasswordAuth(WebhookCommonConfig webhookConfig, WebTarget webTarget) {
    if (webhookConfig.authType == AuthenticationType.BASIC) {
      webTarget.register(
          HttpAuthenticationFeature.basic(webhookConfig.username, webhookConfig.password)
      );
    }

    if (webhookConfig.authType == AuthenticationType.DIGEST) {
      webTarget.register(
          HttpAuthenticationFeature.digest(webhookConfig.username, webhookConfig.password)
      );
    }

    if (webhookConfig.authType == AuthenticationType.UNIVERSAL) {
      webTarget.register(
          HttpAuthenticationFeature.universal(webhookConfig.username, webhookConfig.password)
      );
    }
  }
}

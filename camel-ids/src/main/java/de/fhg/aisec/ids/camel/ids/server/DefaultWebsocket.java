/*-
 * ========================LICENSE_START=================================
 * camel-ids
 * %%
 * Copyright (C) 2018 Fraunhofer AISEC
 * %%
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *      http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * =========================LICENSE_END==================================
 */
package de.fhg.aisec.ids.camel.ids.server;

import com.google.protobuf.InvalidProtocolBufferException;
import de.fhg.aisec.ids.api.conm.RatResult;
import de.fhg.aisec.ids.api.infomodel.InfoModel;
import de.fhg.aisec.ids.api.settings.Settings;
import de.fhg.aisec.ids.camel.ids.CamelComponent;
import de.fhg.aisec.ids.camel.ids.connectionmanagement.ConnectionManagerService;
import de.fhg.aisec.ids.comm.CertificatePair;
import de.fhg.aisec.ids.comm.server.ServerConfiguration;
import de.fhg.aisec.ids.comm.ws.protocol.ProtocolState;
import de.fhg.aisec.ids.comm.ws.protocol.ServerProtocolMachine;
import de.fhg.aisec.ids.comm.ws.protocol.fsm.Event;
import de.fhg.aisec.ids.comm.ws.protocol.fsm.FSM;
import de.fhg.aisec.ids.messages.AttestationProtos.IdsAttestationType;
import de.fhg.aisec.ids.messages.Idscp.ConnectorMessage;
import org.eclipse.jetty.websocket.api.CloseStatus;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.annotations.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

@WebSocket
public class DefaultWebsocket {
  private static final Logger LOG = LoggerFactory.getLogger(DefaultWebsocket.class);

  private final WebsocketConsumer consumer;
  private final NodeSynchronization sync;
  private Session session;
  private String connectionKey;
  private final String pathSpec;
  private FSM idsFsm;
  private CertificatePair certificatePair;

  public DefaultWebsocket(
      NodeSynchronization sync, String pathSpec, WebsocketConsumer consumer, CertificatePair certificatePair) {
    this.sync = sync;
    this.consumer = consumer;
    this.pathSpec = pathSpec;
    this.certificatePair = certificatePair;
  }

  @OnWebSocketClose
  public void onClose(int closeCode, String message) {
    LOG.trace("onClose {} {}", closeCode, message);
    sync.removeSocket(this);
  }

  @OnWebSocketConnect
  public void onConnect(Session session) {
    LOG.trace("onConnect {}", session);
    this.session = session;
    this.connectionKey = UUID.randomUUID().toString();
    IdsAttestationType type;
    int attestationMask = 0;
    switch (this.consumer.getAttestationType()) {
      case 1:
        type = IdsAttestationType.ALL;
        break;
      case 2:
        type = IdsAttestationType.ADVANCED;
        attestationMask = this.consumer.getAttestationMask();
        break;
      case 0:
      default:
        type = IdsAttestationType.BASIC;
    }
    // Integrate server-side of IDS protocol
    Settings settings = ConnectionManagerService.getSettings();
    URI ttpUri = null;
    try {
	    if (settings != null) {
	      ttpUri = new URI(String.format(
	          "https://%s:%d/rat-verify",
            settings.getConnectorConfig().getTtpHost(),
            settings.getConnectorConfig().getTtpPort()
        ));
	    }
    } catch (URISyntaxException e) {
    	LOG.error("incorrect TTP URI syntax", e);
    }
    InfoModel infoModel = CamelComponent.getInfoModelManager();
    ServerConfiguration configuration = new ServerConfiguration.Builder()
        .attestationType(type)
        .attestationMask(attestationMask)
        .certificatePair(certificatePair)
        .rdfDescription(
                infoModel == null
                        ? "{\"message\":\"No InfomodelManager loaded\"}"
                        : infoModel.getConnectorAsJsonLd())
        .ttpUrl(ttpUri)
        .build();
    idsFsm = new ServerProtocolMachine(session, configuration);
    sync.addSocket(this);
  }

  @OnWebSocketMessage
  public void onMessage(String message) {
    LOG.debug("onMessage: {}", message);
    // Check if fsm is in its final state If not, this message is not our department
    if (idsFsm.getState().equals(ProtocolState.IDSCP_END.id())) {
      if (this.consumer != null) {
        this.consumer.sendMessage(this.connectionKey, message);
      } else {
        LOG.warn("No consumer to handle message received: {}", message);
      }
      return;
    }
    // Otherwise, we are still in the process of running IDS protocol and hold back the original
    // message. In this case, feed the message into the protocol FSM
    try {
      ConnectorMessage msg = ConnectorMessage.parseFrom(message.getBytes());
      // we de-protobuf and split messages into cmd and payload
      idsFsm.feedEvent(new Event(msg.getType(), message, msg));
    } catch (InvalidProtocolBufferException e) {
      // An invalid message has been received during IDS protocol. close connection
      LOG.error(e.getMessage(), e);
      this.session.close(new CloseStatus(403, "invalid protobuf"));
    }
  }

  @OnWebSocketMessage
  public void onMessage(byte[] data, int offset, int length) {
    LOG.trace("server received {} byte in onMessage", length);
    if (idsFsm.getState().equals(ProtocolState.IDSCP_END.id())) {
      if (this.consumer != null) {
        this.consumer.sendMessage(this.connectionKey, data);
      } else {
        LOG.debug("No consumer to handle message received: {}", data);
      }
    } else {
      try {
        ConnectorMessage msg = ConnectorMessage.parseFrom(data);
        idsFsm.feedEvent(
            new Event(
                msg.getType(),
                new String(data, StandardCharsets.UTF_8),
                msg)); // we need to de-protobuf here and split messages into cmd and payload
      } catch (IOException e) {
        // An invalid message has been received during IDS protocol. close connection
        LOG.error("Closing session because " + e.getMessage(), e);
        this.session.close(new CloseStatus(403, "invalid protobuf"));
      }
    }
  }

  @OnWebSocketError
  public void onError(Session session, Throwable t) {
    LOG.error(t.getMessage() + " Host: " + session.getRemoteAddress().getHostName(), t);
  }

  public Session getSession() {
    return session;
  }

  public String getPathSpec() {
    return pathSpec;
  }

  public void setSession(Session session) {
    this.session = session;
  }

  public String getConnectionKey() {
    return connectionKey;
  }

  public void setConnectionKey(String connectionKey) {
    this.connectionKey = connectionKey;
  }

  // get the current State of the FSM
  public String getCurrentProtocolState() {
    return idsFsm.getState();
  }

  // get the result of the remote attestation
  public RatResult getAttestationResult() {
    return idsFsm.getRatResult();
  }

  public String getMetaResult() {
    return idsFsm.getMetaData();
  }

  public String getRemoteHostname() {
    return session.getRemoteAddress().getHostName();
  }
}

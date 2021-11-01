/**
 * Copyright © 2016-2021 The Thingsboard Authors
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
package org.thingsboard.server.service.lwm2m;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.codec.binary.Base64;
import org.eclipse.leshan.core.util.Hex;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Service;
import org.thingsboard.server.common.data.device.profile.lwm2mTransportConfiguration.BootstrapServerConfigDao;
import org.thingsboard.server.transport.lwm2m.config.LwM2MSecureServerConfig;
import org.thingsboard.server.transport.lwm2m.config.LwM2MTransportBootstrapConfig;
import org.thingsboard.server.transport.lwm2m.config.LwM2MTransportServerConfig;
import java.security.KeyStore;
import java.security.cert.X509Certificate;

@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnExpression("('${service.type:null}'=='tb-transport' && '${transport.lwm2m.enabled:false}'=='true') || '${service.type:null}'=='monolith' || '${service.type:null}'=='tb-core'")
public class LwM2MServiceImpl implements LwM2MService {

    private final LwM2MTransportServerConfig serverConfig;
    private final LwM2MTransportBootstrapConfig bootstrapConfig;

    @Override
    public BootstrapServerConfigDao getServerSecurityInfo(boolean bootstrapServer) {
        BootstrapServerConfigDao result = getServerSecurityConfig(bootstrapServer ? bootstrapConfig : serverConfig);
        result.setBootstrapServerIs(bootstrapServer);
        return result;
    }

    private BootstrapServerConfigDao getServerSecurityConfig(LwM2MSecureServerConfig serverConfig) {
        BootstrapServerConfigDao bsServ = new BootstrapServerConfigDao();
        bsServ.setServerId(serverConfig.getId());
        bsServ.setHost(serverConfig.getHost());
        bsServ.setPort(serverConfig.getPort());
        bsServ.setSecurityHost(serverConfig.getSecureHost());
        bsServ.setSecurityPort(serverConfig.getSecurePort());
        byte[] publicKeyBase64 = getPublicKey(serverConfig);
        if (publicKeyBase64 == null) {
            bsServ.setServerPublicKey("");
        } else {
            bsServ.setServerPublicKey(Base64.encodeBase64String(getPublicKey(serverConfig)));
        }
        return bsServ;
    }

    private byte[] getPublicKey(LwM2MSecureServerConfig config) {
        try {
            KeyStore keyStore = serverConfig.getKeyStoreValue();
            if (keyStore != null) {
                X509Certificate serverCertificate = (X509Certificate) serverConfig.getKeyStoreValue().getCertificate(config.getCertificateAlias());
                return serverCertificate.getPublicKey().getEncoded();
            }
        } catch (Exception e) {
            log.trace("Failed to fetch public key from key store!", e);
        }
        return null;
    }
}


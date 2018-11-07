/*
 * Copyright (c) 2018 Mastercard
 * Copyright (c) 2018 Gluu
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and limitations under the License.
 */

package org.gluu.oxauth.fido2.attestation;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.charset.Charset;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;
import java.util.stream.Collectors;

import javax.inject.Inject;
import javax.inject.Named;

import org.gluu.oxauth.fido2.ctap.AttestationConveyancePreference;
import org.gluu.oxauth.fido2.model.entry.Fido2RegistrationData;
import org.gluu.oxauth.fido2.model.entry.Fido2RegistrationEntry;
import org.gluu.oxauth.fido2.model.entry.RegistrationStatus;
import org.gluu.oxauth.fido2.persist.Fido2AuthenticationPersistenceService;
import org.gluu.oxauth.fido2.persist.Fido2RegistrationPersistenceService;
import org.gluu.oxauth.fido2.service.AuthenticatorAttestationVerifier;
import org.gluu.oxauth.fido2.service.ChallengeGenerator;
import org.gluu.oxauth.fido2.service.ChallengeVerifier;
import org.gluu.oxauth.fido2.service.CommonVerifiers;
import org.gluu.oxauth.fido2.service.CredAndCounterData;
import org.gluu.oxauth.fido2.service.DomainVerifier;
import org.gluu.oxauth.fido2.service.Fido2RPRuntimeException;
import org.slf4j.Logger;
import org.xdi.oxauth.model.configuration.AppConfiguration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

@Named
class AttestationService {

    @Inject
    private Logger log;

    @Inject
    private Fido2AuthenticationPersistenceService authenticationsRepository;

    @Inject
    private Fido2RegistrationPersistenceService registrationsRepository;
    @Inject
    private AuthenticatorAttestationVerifier authenticatorAttestationVerifier;
    @Inject
    private ChallengeVerifier challengeVerifier;
    @Inject
    private DomainVerifier domainVerifier;

    @Inject
    private ChallengeGenerator challengeGenerator;

    @Inject
    private CommonVerifiers commonVerifiers;
    @Inject
    private ObjectMapper om;
    @Inject
    @Named("base64UrlEncoder")
    private Base64.Encoder base64UrlEncoder;
    @Inject
    @Named("base64UrlDecoder")
    private Base64.Decoder base64UrlDecoder;

    @Inject
    private AppConfiguration appConfiguration;

    JsonNode options(JsonNode params) {
        log.info("options {}", params);
        return createNewRegistration(params);
    }

    JsonNode verify(JsonNode params) {
        log.info("registerResponse {}", params);
        // JsonNode request = params.get("request");

        commonVerifiers.verifyBasicPayload(params);
        commonVerifiers.verifyBase64UrlString(params.get("type"));
        JsonNode response = params.get("response");
        JsonNode clientDataJSONNode = null;
        try {
            clientDataJSONNode = om
                    .readTree(new String(base64UrlDecoder.decode(params.get("response").get("clientDataJSON").asText()), Charset.forName("UTF-8")));
        } catch (IOException e) {
            new Fido2RPRuntimeException("Can't parse message");
        }

        commonVerifiers.verifyClientJSON(clientDataJSONNode);
        commonVerifiers.verifyClientJSONTypeIsCreate(clientDataJSONNode);
        JsonNode keyIdNode = params.get("id");
        String keyId = commonVerifiers.verifyBase64UrlString(keyIdNode);

        String clientDataChallenge = base64UrlEncoder.withoutPadding()
                .encodeToString(base64UrlDecoder.decode(clientDataJSONNode.get("challenge").asText()));
        log.info("Challenge {}", clientDataChallenge);
        // String clientDataOrigin = clientDataJSONNode.get("origin").asText();

        List<Fido2RegistrationEntry> registrationEntries = registrationsRepository.findAllByChallenge(clientDataChallenge);
        Fido2RegistrationData credentialFound = registrationEntries.parallelStream().findAny()
                .orElseThrow(() -> new Fido2RPRuntimeException("Can't find request with matching challenge and domain")).getRegistrationData();

        domainVerifier.verifyDomain(credentialFound.getDomain(), clientDataJSONNode.get("origin").asText());
        CredAndCounterData attestationData = authenticatorAttestationVerifier.verifyAuthenticatorAttestationResponse(response, credentialFound);

        credentialFound.setUncompressedECPoint(attestationData.getUncompressedEcPoint());
        credentialFound.setStatus(RegistrationStatus.REGISTERED);
        credentialFound.setW3cAuthenticatorAttenstationResponse(response.toString());
        credentialFound.setSignatureAlgorithm(attestationData.getSignatureAlgorithm());
        credentialFound.setCounter(attestationData.getCounters());
        if (attestationData.getCredId() != null) {
            credentialFound.setPublicKeyId(attestationData.getCredId());
        } else {
            credentialFound.setPublicKeyId(keyId);
        }
        credentialFound.setType("public-key");
        registrationsRepository.save(credentialFound);
        // ArrayNode excludedCredentials = ((ObjectNode)
        // params).putArray("excludeCredentials");

        ((ObjectNode) params).put("errorMessage", "");
        ((ObjectNode) params).put("status", "ok");
        return params;
    }

    private JsonNode createNewRegistration(JsonNode params) {
        commonVerifiers.verifyOptions(params);
        String username = params.get("username").asText();
        String displayName = params.get("displayName").asText();

        String documentDomain;
        String host;
        if (params.hasNonNull("documentDomain")) {
            documentDomain = params.get("documentDomain").asText();
        } else {
            documentDomain = appConfiguration.getIssuer();
        }

        try {
            host = new URL(documentDomain).getHost();
        } catch (MalformedURLException e) {
            host = documentDomain;
            // throw new Fido2RPRuntimeException(e.getMessage());
        }

        String authenticatorSelection;
        if (params.hasNonNull("authenticatorSelection")) {
            authenticatorSelection = params.get("authenticatorSelection").asText();
        } else {
            authenticatorSelection = "";
        }

        log.info("Options {} {} {}", username, displayName, documentDomain);
        AttestationConveyancePreference attestationConveyancePreference = commonVerifiers.verifyAttestationConveyanceType(params);

        String credentialType = params.hasNonNull("credentialType") ? params.get("credentialType").asText("public-key") : "public-key";
        ObjectNode credentialCreationOptionsNode = om.createObjectNode();

        String challenge = challengeGenerator.getChallenge();
        credentialCreationOptionsNode.put("challenge", challenge);
        log.info("Challenge {}", challenge);
        ObjectNode credentialRpEntityNode = credentialCreationOptionsNode.putObject("rp");
        credentialRpEntityNode.put("name", "Mastercard RP");
        credentialRpEntityNode.put("id", documentDomain);

        ObjectNode credentialUserEntityNode = credentialCreationOptionsNode.putObject("user");
        byte[] buffer = new byte[32];
        new SecureRandom().nextBytes(buffer);
        String userId = base64UrlEncoder.encodeToString(buffer);
        credentialUserEntityNode.put("id", userId);
        credentialUserEntityNode.put("name", username);
        credentialUserEntityNode.put("displayName", displayName);
        credentialCreationOptionsNode.put("attestation", attestationConveyancePreference.toString());
        ArrayNode credentialParametersArrayNode = credentialCreationOptionsNode.putArray("pubKeyCredParams");
        ObjectNode credentialParametersNode = credentialParametersArrayNode.addObject();
        if ("public-key".equals(credentialType)) {
            credentialParametersNode.put("type", "public-key");
            credentialParametersNode.put("alg", -7);
        }
        if ("FIDO".equals(credentialType)) {
            credentialParametersNode.put("type", "FIDO");
            credentialParametersNode.put("alg", -7);
        }
        credentialCreationOptionsNode.set("authenticatorSelection", params.get("authenticatorSelection"));

        List<Fido2RegistrationEntry> existingRegistrationEntries = registrationsRepository.findAllByUsername(username);
        List<JsonNode> excludedKeys = existingRegistrationEntries.parallelStream()
                .map(f -> om.convertValue(new PublicKeyCredentialDescriptor(f.getRegistrationData().getType(), f.getRegistrationData().getPublicKeyId()), JsonNode.class))
                .collect(Collectors.toList());

        ArrayNode excludedCredentials = credentialCreationOptionsNode.putArray("excludeCredentials");
        excludedCredentials.addAll(excludedKeys);
        credentialCreationOptionsNode.put("status", "ok");
        credentialCreationOptionsNode.put("errorMessage", "");
        Fido2RegistrationData entity = new Fido2RegistrationData();
        entity.setUsername(username);
        entity.setUserId(userId);
        entity.setChallenge(challenge);
        entity.setDomain(host);
        entity.setW3cCredentialCreationOptions(credentialCreationOptionsNode.toString());
        entity.setAttestationConveyancePreferenceType(attestationConveyancePreference);
        registrationsRepository.save(entity);
        return credentialCreationOptionsNode;
    }

}

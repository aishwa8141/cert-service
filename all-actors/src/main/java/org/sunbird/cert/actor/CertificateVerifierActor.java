package org.sunbird.cert.actor;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.log4j.Logger;
import org.incredible.UrlManager;
import org.incredible.certProcessor.CertificateFactory;
import org.incredible.certProcessor.signature.exceptions.SignatureException;
import org.incredible.certProcessor.store.CertStoreFactory;
import org.incredible.certProcessor.store.ICertStore;
import org.incredible.certProcessor.store.StoreConfig;
import org.sunbird.BaseActor;
import org.sunbird.BaseException;
import org.sunbird.CertsConstant;
import org.sunbird.JsonKey;
import org.sunbird.actor.core.ActorConfig;
import org.sunbird.cloud.storage.exception.StorageServiceException;
import org.sunbird.message.IResponseMessage;
import org.sunbird.message.ResponseCode;
import org.sunbird.request.Request;
import org.sunbird.response.Response;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.text.MessageFormat;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * This actor is responsible for certificate verification.
 */
@ActorConfig(
        tasks = {JsonKey.VERIFY_CERT},
        asyncTasks = {}
)
public class CertificateVerifierActor extends BaseActor {

    private Logger logger = Logger.getLogger(CertificateVerifierActor.class);

    private ObjectMapper mapper = new ObjectMapper();

    private CertsConstant certsConstant = new CertsConstant();

    @Override
    public void onReceive(Request request) throws Throwable {
        String operation = request.getOperation();
        logger.info("onReceive method call start for operation " + operation);
        if (JsonKey.VERIFY_CERT.equalsIgnoreCase(operation)) {
            verifyCertificate(request);
        }
    }

    private void verifyCertificate(Request request) throws BaseException {
        Map<String, Object> certificate = new HashMap<>();
        VerificationResponse verificationResponse = new VerificationResponse();
        try {
            if (((Map) request.get(JsonKey.CERTIFICATE)).containsKey(JsonKey.DATA)) {
                certificate = (Map<String, Object>) ((Map) request.get(JsonKey.CERTIFICATE)).get(JsonKey.DATA);
            } else if (((Map) request.get(JsonKey.CERTIFICATE)).containsKey(JsonKey.ID)) {
                certificate = downloadCert((String) ((Map<String, Object>) request.get(JsonKey.CERTIFICATE)).get(JsonKey.ID));
            }
            logger.debug("Certificate extension " + certificate);
            List<String> certificateType = (List<String>) ((Map) certificate.get(JsonKey.VERIFICATION)).get(JsonKey.TYPE);
            if (JsonKey.HOSTED.equals(certificateType.get(0))) {
                verificationResponse = verifyHostedCertificate(certificate);
            } else if (JsonKey.SIGNED_BADGE.equals(certificateType.get(0))) {
                verificationResponse = verifySignedCertificate(certificate);
            }
        } catch (IOException | SignatureException.UnreachableException | SignatureException.VerificationException ex) {
            logger.error("verifySignedCertificate:Exception Occurred while verifying certificate. : " + ex.getMessage());
            throw new BaseException(IResponseMessage.INTERNAL_ERROR, ex.getMessage(), ResponseCode.SERVER_ERROR.getCode());
        }
        Response response = new Response();
        response.getResult().put("response", verificationResponse);
        sender().tell(response, getSelf());
        logger.info("onReceive method call End");
    }

    /**
     * Verifies signed certificate , verify signature value nad expiry date
     *
     * @param certificate certificate object
     * @return
     * @throws SignatureException.UnreachableException
     * @throws SignatureException.VerificationException
     */
    private VerificationResponse verifySignedCertificate(Map<String, Object> certificate) throws SignatureException.UnreachableException, SignatureException.VerificationException {
        List<String> messages = new ArrayList<>();
        CollectionUtils.addIgnoreNull(messages, verifySignature(certificate));
        CollectionUtils.addIgnoreNull(messages, verifyExpiryDate((String) certificate.get(JsonKey.EXPIRES)));
        return getVerificationResponse(messages);
    }

    /**
     * verifies the hosted certificate
     * verifies expiry date
     *
     * @param certificate certificate object
     * @return
     */
    private VerificationResponse verifyHostedCertificate(Map<String, Object> certificate) {
        List<String> messages = new ArrayList<>();
        messages.add(verifyExpiryDate((String) certificate.get(JsonKey.EXPIRES)));
        messages.removeAll(Collections.singleton(null));
        return getVerificationResponse(messages);
    }

    private VerificationResponse getVerificationResponse(List<String> messages) {
        VerificationResponse verificationResponse = new VerificationResponse();
        if (messages.size() == 0) {
            verificationResponse.setValid(true);
        } else {
            verificationResponse.setValid(false);
        }
        verificationResponse.setErrorCount(messages.size());
        verificationResponse.setMessages(messages);
        return verificationResponse;

    }


    /**
     * to download certificate from cloud
     *
     * @param url
     * @return
     * @throws IOException
     * @throws BaseException
     */
    private Map<String, Object> downloadCert(String url) throws IOException, BaseException {
        StoreConfig storeConfig = new StoreConfig(certsConstant.getStorageParamsFromEvn());
        CertStoreFactory certStoreFactory = new CertStoreFactory(null);
        ICertStore certStore = certStoreFactory.getCloudStore(storeConfig);
        certStore.init();
        try {
            String filePath = "conf/";
            String uri = UrlManager.getUri(url);
            certStore.get(uri);
            File file = new File(filePath + getFileName(uri));
            Map<String, Object> certificate = mapper.readValue(file, new TypeReference<Map<String, Object>>() {
            });
            file.delete();
            return certificate;
        } catch (StorageServiceException ex) {
            logger.error("downloadCertJson:Exception Occurred while downloading json certificate from the cloud. : " + ex.getMessage());
            throw new BaseException("INVALID_PARAM_VALUE", MessageFormat.format(IResponseMessage.INVALID_PARAM_VALUE,
                    url, JsonKey.ID), ResponseCode.CLIENT_ERROR.getCode());
        }
    }

    private String getFileName(String certId) {
        String idStr = null;
        try {
            URI uri = new URI(certId);
            String path = uri.getPath();
            idStr = path.substring(path.lastIndexOf('/') + 1);
        } catch (URISyntaxException e) {
            logger.debug("getFileName : exception occurred while getting file form the uri " + e.getMessage());
        }
        return idStr;
    }

    /**
     * verifying certificate signature value
     *
     * @param certificateExtension
     * @return
     * @throws SignatureException.UnreachableException
     * @throws SignatureException.VerificationException
     */
    private String verifySignature(Map<String, Object> certificateExtension) throws SignatureException.UnreachableException, SignatureException.VerificationException {
        String signatureValue = ((Map<String, String>) certificateExtension.get(JsonKey.SIGNATURE)).get(JsonKey.SIGNATURE_VALUE);
        String message = null;
        certificateExtension.remove(JsonKey.SIGNATURE);
        JsonNode jsonNode = mapper.valueToTree(certificateExtension);
        CertificateFactory certificateFactory = new CertificateFactory();
        Boolean isValid = certificateFactory.verifySignature(jsonNode, signatureValue, certsConstant.getEncryptionServiceUrl(),
                ((Map<String, String>) certificateExtension.get(JsonKey.VERIFICATION)).get(JsonKey.CREATOR));
        if (!isValid) {
            message = "ERROR: Assertion.signature - certificate is not valid , signature verification failed";
        }
        return message;
    }

    private String verifyExpiryDate(String expiryDate) {
        String message = null;
        if (StringUtils.isNotBlank(expiryDate)) {
            SimpleDateFormat simpleDateFormat = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'");
            try {
                Date currentDate = simpleDateFormat.parse(getCurrentDate());
                if (simpleDateFormat.parse(expiryDate).before(currentDate)) {
                    message = "ERROR: Assertion.expires - certificate has been expired";
                }
            } catch (ParseException e) {
                logger.info("verifyExpiryDate : exception occurred parsing date " + e.getMessage());
            }
        }
        return message;
    }

    private String getCurrentDate() {
        DateTimeFormatter dtf = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'");
        LocalDateTime now = LocalDateTime.now();
        return dtf.format(now);
    }


}

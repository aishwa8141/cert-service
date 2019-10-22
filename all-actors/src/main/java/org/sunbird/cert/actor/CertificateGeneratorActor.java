package org.sunbird.cert.actor;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.commons.collections.MapUtils;
import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.BooleanUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.log4j.Logger;
import org.incredible.CertificateGenerator;
import org.incredible.UrlManager;
import org.incredible.certProcessor.CertModel;
import org.incredible.certProcessor.store.CertStoreFactory;
import org.incredible.certProcessor.store.ICertStore;
import org.incredible.certProcessor.store.StoreConfig;
import org.incredible.certProcessor.views.HTMLTemplateZip;
import org.incredible.pojos.CertificateResponse;
import org.sunbird.BaseActor;
import org.sunbird.BaseException;
import org.sunbird.CertMapper;
import org.sunbird.CertsConstant;
import org.sunbird.JsonKey;
import org.sunbird.actor.core.ActorConfig;
import org.sunbird.cert.actor.operation.CertActorOperation;
import org.sunbird.cloud.storage.IStorageService;
import org.sunbird.cloud.storage.factory.StorageConfig;
import org.sunbird.cloud.storage.factory.StorageServiceFactory;
import org.sunbird.message.IResponseMessage;
import org.sunbird.message.ResponseCode;
import org.sunbird.request.Request;
import org.sunbird.response.Response;
import scala.Some;

import java.io.File;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * This actor is responsible for certificate generation.
 *
 * @author manzarul
 */
@ActorConfig(
        tasks = {JsonKey.GENERATE_CERT, JsonKey.GET_SIGN_URL},
        asyncTasks = {}
)
public class CertificateGeneratorActor extends BaseActor {
    private Logger logger = Logger.getLogger(CertificateGeneratorActor.class);
    private static CertsConstant certVar = new CertsConstant();
    private ObjectMapper mapper = new ObjectMapper();

    @Override
    public void onReceive(Request request) throws Throwable {
        String operation = request.getOperation();
        logger.info("onReceive method call start for operation " + operation);
        if (JsonKey.GENERATE_CERT.equalsIgnoreCase(operation)) {
            generateCertificate(request);
        } else if (CertActorOperation.GET_SIGN_URL.getOperation().equalsIgnoreCase(operation)) {
            generateSignUrl(request);
        }
        logger.info("onReceive method call End");
    }

    private void generateSignUrl(Request request) {
        try {
            logger.info("CertificateGeneratorActor:generateSignUrl:generate request got : ".concat(request.getRequest() + ""));
            String uri = UrlManager.getUri((String) request.getRequest().get(JsonKey.PDF_URL));
            logger.info("CertificateGeneratorActor:generateSignUrl:generate sign url method called for uri: ".concat(uri));
            IStorageService storageService = getStorageService();
            String signUrl = storageService.getSignedURL(certVar.getCONTAINER_NAME(), uri, Some.apply(getTimeoutInSeconds()),
                    Some.apply("r"));
            logger.info("CertificateGeneratorActor:generateSignUrl:signedUrl got: ".concat(signUrl));
            Response response = new Response();
            response.put(JsonKey.RESPONSE, JsonKey.SUCCESS);
            response.put(JsonKey.SIGNED_URL, signUrl);
            sender().tell(response, self());
        } catch (Exception e) {
            logger.error("CertificateGeneratorActor:generateSignUrl: error in genrerating sign url " + e);
            Response response = new Response();
            response.put(JsonKey.RESPONSE, "failure");
            response.put(JsonKey.SIGNED_URL, "");
            sender().tell(response, self());
        }

    }


    private IStorageService getStorageService() {
        StorageConfig storageConfig = new StorageConfig(certVar.getCloudStorageType(), certVar.getAzureStorageKey(), certVar.getAzureStorageSecret());
        logger.info("CertificateGeneratorActor:getStorageService:storage object formed:".concat(storageConfig.toString()));
        IStorageService storageService = StorageServiceFactory.getStorageService(storageConfig);
        return storageService;
    }

    private int getTimeoutInSeconds() {
        String timeoutInSecondsStr = CertsConstant.getExpiryLink(CertsConstant.DOWNLOAD_LINK_EXPIRY_TIMEOUT);
        logger.info("CertificateGeneratorActor:getTimeoutInSeconds:timeout got: ".concat(timeoutInSecondsStr));
        return Integer.parseInt(timeoutInSecondsStr);
    }

    private void generateCertificate(Request request) throws BaseException {
        logger.info("Request received==" + request.getRequest());
        HashMap<String, String> properties = populatePropertiesMap(request);
        CertStoreFactory certStoreFactory = new CertStoreFactory(properties);
        String templateUrl = (String) ((Map<String, Object>) request.getRequest().get(JsonKey.CERTIFICATE)).get(JsonKey.HTML_TEMPLATE);
        StoreConfig storeParams = new StoreConfig(getStorageParamsFromRequestOrEnv((Map<String, Object>) ((Map) request.get(JsonKey.CERTIFICATE)).get(JsonKey.STORE)));
        ICertStore htmlTemplateStore = certStoreFactory.getHtmlTemplateStore(templateUrl, storeParams);
        ICertStore certStore = certStoreFactory.getCertStore(storeParams, BooleanUtils.toBoolean(properties.get(JsonKey.PREVIEW)));
        CertMapper certMapper = new CertMapper(properties);
        List<CertModel> certModelList = certMapper.toList(request.getRequest());
        HTMLTemplateZip htmlTemplateZip;
        try {
            htmlTemplateZip = new HTMLTemplateZip(htmlTemplateStore, templateUrl);
            logger.info("CertificateGeneratorActor:generateCertificate:html zip generated");
        } catch (Exception ex) {
            logger.error("CertificateGeneratorActor:generateCertificate:Exception Occurred while creating HtmlTemplate provider.", ex);
            throw new BaseException("INVALID_PARAM_VALUE", MessageFormat.format(IResponseMessage.INVALID_PARAM_VALUE, templateUrl, JsonKey.HTML_TEMPLATE), ResponseCode.CLIENT_ERROR.getCode());
        }
        String directory = certStoreFactory.getDirectoryName(StringUtils.substringBefore(htmlTemplateZip.getZipFileName(), ".zip"));
        CertificateGenerator certificateGenerator = new CertificateGenerator(properties, directory);
        List<Map<String, Object>> certUrlList = new ArrayList<>();
        for (CertModel certModel : certModelList) {
            CertificateResponse certificateResponse = new CertificateResponse();
            try {
                certificateResponse = certificateGenerator.createCertificate(certModel, htmlTemplateZip);
                Map<String, Object> uploadRes = uploadCertificate(directory + certificateResponse.getUuid(), certStore, certStoreFactory.setCloudPath(storeParams));
                certUrlList.add(getResponse(certificateResponse, uploadRes));
            } catch (Exception ex) {
                logger.error("CertificateGeneratorActor:generateCertificate:Exception Occurred while generating certificate. : " + ex.getMessage());
                throw new BaseException(IResponseMessage.INTERNAL_ERROR, ex.getMessage(), ResponseCode.SERVER_ERROR.getCode());
            } finally {
                certStoreFactory.cleanUp(certificateResponse.getUuid(), directory);
            }
        }
        Response response = new Response();
        response.getResult().put("response", certUrlList);
        sender().tell(response, getSelf());
        logger.info("onReceive method call End");
    }

    private Map<String, Object> uploadCertificate(String fileName, ICertStore certStore, String cloudPath) throws BaseException, IOException {
        certStore.init();
        Map<String, Object> resMap = new HashMap<>();
        File file = FileUtils.getFile(fileName.concat(".pdf"));
        resMap.put(JsonKey.PDF_URL, certStore.save(file, cloudPath));
        file = FileUtils.getFile(fileName.concat(".json"));
        resMap.put(JsonKey.JSON_URL, certStore.save(file, cloudPath));
        if (StringUtils.isBlank((String) resMap.get(JsonKey.PDF_URL)) || StringUtils.isBlank((String) resMap.get(JsonKey.JSON_URL))) {
            logger.error("CertificateGeneratorActor:uploadCertificate:Exception Occurred while uploading certificate pdfUrl and jsonUrl is null");
            throw new BaseException("INTERNAL_SERVER_ERROR", IResponseMessage.ERROR_UPLOADING_CERTIFICATE, ResponseCode.SERVER_ERROR.getCode());
        }
        return resMap;
    }


    private Map<String, Object> getResponse(CertificateResponse certificateResponse, Map<String, Object> uploadRes) {
        Map<String, Object> resMap = new HashMap<>();
        resMap.put(JsonKey.UNIQUE_ID, certificateResponse.getUuid());
        resMap.put(JsonKey.RECIPIENT_ID, certificateResponse.getRecipientId());
        resMap.put(JsonKey.ACCESS_CODE, certificateResponse.getAccessCode());
        try {
            resMap.put(JsonKey.JSON_DATA, mapper.readValue(certificateResponse.getJsonData(), Map.class));
        } catch (IOException e) {
            e.printStackTrace();
        }
        resMap.putAll(uploadRes);
        return resMap;
    }

    private HashMap<String, String> populatePropertiesMap(Request request) throws BaseException {
        HashMap<String, String> properties = new HashMap<>();
        String tag = (String) ((Map) request.get(JsonKey.CERTIFICATE)).get(JsonKey.TAG);
        String preview = (String) ((Map<String, Object>) request.getRequest().get(JsonKey.CERTIFICATE)).get(JsonKey.PREVIEW);
        Map<String, Object> keysObject = (Map<String, Object>) ((Map) request.get(JsonKey.CERTIFICATE)).get(JsonKey.KEYS);
        certVar.setBasePath((String) ((Map<String, Object>) request.getRequest().get(JsonKey.CERTIFICATE))
                .get(JsonKey.BASE_PATH));
        if (MapUtils.isNotEmpty(keysObject)) {
            String keyId = (String) keysObject.get(JsonKey.ID);
            properties.put(JsonKey.KEY_ID, keyId);
            properties.put(JsonKey.SIGN_CREATOR, certVar.getSignCreator(keyId));
            properties.put(JsonKey.PUBLIC_KEY_URL, certVar.getPUBLIC_KEY_URL(keyId));
            logger.info("populatePropertiesMap: keys after".concat(keyId));
        }
        properties.put(JsonKey.TAG, tag);
        properties.put(JsonKey.CONTAINER_NAME, certVar.getCONTAINER_NAME());
        properties.put(JsonKey.BADGE_URL, certVar.getBADGE_URL(tag));
        properties.put(JsonKey.ISSUER_URL, certVar.getISSUER_URL());
        properties.put(JsonKey.CONTEXT, certVar.getCONTEXT());
        properties.put(JsonKey.VERIFICATION_TYPE, certVar.getVERIFICATION_TYPE());
        properties.put(JsonKey.ACCESS_CODE_LENGTH, certVar.getACCESS_CODE_LENGTH());
        properties.put(JsonKey.SIGN_URL, certVar.getEncSignUrl());
        properties.put(JsonKey.SIGN_VERIFY_URL, certVar.getEncSignVerifyUrl());
        properties.put(JsonKey.ENC_SERVICE_URL, certVar.getEncryptionServiceUrl());
        properties.put(JsonKey.SIGNATORY_EXTENSION, certVar.getSignatoryExtensionUrl());
        properties.put(JsonKey.SLUG, certVar.getSlug());
        properties.put(JsonKey.PREVIEW, certVar.getPreview(preview));
        properties.put(JsonKey.BASE_PATH, certVar.getBasePath());

        logger.info("CertificateGeneratorActor:getProperties:properties got from Constant File ".concat(Collections.singleton(properties.toString()) + ""));
        return properties;
    }

    private Map<String, Object> getStorageParamsFromRequestOrEnv(Map<String, Object> storeParams) {
        if (MapUtils.isNotEmpty(storeParams)) {
            return storeParams;
        } else {
            return certVar.getStorageParamsFromEvn();
        }
    }

}

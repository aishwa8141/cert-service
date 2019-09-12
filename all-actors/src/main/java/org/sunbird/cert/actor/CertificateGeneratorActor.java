package org.sunbird.cert.actor;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.commons.collections.MapUtils;
import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.log4j.Logger;
import org.incredible.CertificateGenerator;
import org.incredible.certProcessor.CertModel;
import org.incredible.certProcessor.store.StorageParams;
import org.incredible.certProcessor.views.HTMLTempalteZip;
import org.incredible.pojos.CertificateResponse;
import org.sunbird.*;
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
import java.text.MessageFormat;
import java.util.*;

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
            String uri = (String) request.getRequest().get(JsonKey.PDF_URL);
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
        CertMapper certMapper = new CertMapper(properties);
        List<CertModel> certModelList = certMapper.toList(request.getRequest());
        CertificateGenerator certificateGenerator = new CertificateGenerator(properties);
        HTMLTempalteZip htmlTempalteZip = null;
        String directory;
        String url = (String) ((Map<String, Object>) request.getRequest().get(JsonKey.CERTIFICATE)).get(JsonKey.HTML_TEMPLATE);
        try {
            htmlTempalteZip = new HTMLTempalteZip(url, properties);
            logger.info("CertificateGeneratorActor:generateCertificate:html zip generated");
        } catch (Exception ex) {
            logger.error("CertificateGeneratorActor:generateCertificate:Exception Occurred while creating HtmlTemplate provider.", ex);
            throw new BaseException("INVALID_PARAM_VALUE", MessageFormat.format(IResponseMessage.INVALID_PARAM_VALUE, url, JsonKey.HTML_TEMPLATE), ResponseCode.CLIENT_ERROR.getCode());
        }
        String orgId = (String) ((Map) request.get(JsonKey.CERTIFICATE)).get(JsonKey.ORG_ID);
        String tag = (String) ((Map) request.get(JsonKey.CERTIFICATE)).get(JsonKey.TAG);
        directory = getDirectoryName(orgId, tag, htmlTempalteZip.getZipFileName(), properties);
        List<Map<String, Object>> certUrlList = new ArrayList<>();
        for (CertModel certModel : certModelList) {
            CertificateResponse certificateResponse = new CertificateResponse();
            try {
                certificateResponse = certificateGenerator.createCertificate(certModel, htmlTempalteZip, directory);
            } catch (Exception ex) {
                cleanup(directory, certificateResponse.getUuid());
                logger.error("CertificateGeneratorActor:generateCertificate:Exception Occurred while generating certificate. : " + ex.getMessage());
                throw new BaseException(IResponseMessage.INTERNAL_ERROR, ex.getMessage(), ResponseCode.SERVER_ERROR.getCode());
            }
            if(checkStorageParamsExist(properties))    {
                certUrlList.add(uploadCertificate(certificateResponse, certModel.getIdentifier(), orgId, tag, directory));
                cleanup(directory, certificateResponse.getUuid());
            }
            else {
                certUrlList.add(getResponse(certificateResponse, certModel.getIdentifier(),  htmlTempalteZip.getZipFileName()));
            }
        }
        Response response = new Response();
        response.getResult().put("response", certUrlList);
        sender().tell(response, getSelf());
        logger.info("onReceive method call End");
    }

    private  Map<String, Object>  getResponse(CertificateResponse certificateResponse, String recipientID, String directory )   {
        Map<String, Object> resMap = new HashMap<>();
        String uuid = certificateResponse.getUuid();
        String certFileName = certVar.getDOMAIN_URL() + "/" + JsonKey.ASSETS + "/" + directory + "/" + uuid;
        resMap.put(JsonKey.PDF_URL, certFileName +".pdf" );
        resMap.put(JsonKey.JSON_URL, certFileName + ".json");
        resMap.put(JsonKey.UNIQUE_ID, certificateResponse.getUuid());
        resMap.put(JsonKey.RECIPIENT_ID, recipientID);
        resMap.put(JsonKey.ACCESS_CODE, certificateResponse.getAccessCode());
        resMap.put(JsonKey.PREVIEW, true);
        try {
            resMap.put(JsonKey.JSON_DATA, mapper.readValue(certificateResponse.getJsonData(), Map.class));
        } catch (IOException e) {
            e.printStackTrace();
        }
        return resMap;
    }

    private void cleanup(String path, String fileName) {
        try {
            if(StringUtils.isNotBlank(fileName))    {
                File directory = new File(path);
                File[] files = directory.listFiles();
                for (File file : files) {
                    if (file.getName().startsWith(fileName)) file.delete();
                }
                logger.info("CertificateGeneratorActor: cleanUp completed");
            }
        } catch (Exception ex) {
            logger.error(ex.getMessage(), ex);
        }
    }

    private Map<String, Object> uploadCertificate(CertificateResponse certificateResponse
            , String recipientID, String orgId, String batchId, String directory) throws BaseException {
        Map<String, Object> resMap = new HashMap<>();
        String certFileName = certificateResponse.getUuid() + ".pdf";
        resMap.put(JsonKey.PDF_URL, upload(certFileName, orgId, batchId, directory));
        certFileName = certificateResponse.getUuid() + ".json";
        resMap.put(JsonKey.JSON_URL, upload(certFileName, orgId, batchId, directory));
        resMap.put(JsonKey.UNIQUE_ID, certificateResponse.getUuid());
        resMap.put(JsonKey.RECIPIENT_ID, recipientID);
        resMap.put(JsonKey.ACCESS_CODE, certificateResponse.getAccessCode());
        resMap.put(JsonKey.PREVIEW, false);
        try {
            resMap.put(JsonKey.JSON_DATA, mapper.readValue(certificateResponse.getJsonData(), Map.class));
        } catch (IOException e) {
            e.printStackTrace();
        }
        if (StringUtils.isBlank((String) resMap.get(JsonKey.PDF_URL)) || StringUtils.isBlank((String) resMap.get(JsonKey.JSON_URL))) {
            logger.error("CertificateGeneratorActor:uploadCertificate:Exception Occurred while uploading certificate pdfUrl and jsonUrl is null");
            throw new BaseException("INTERNAL_SERVER_ERROR", IResponseMessage.ERROR_UPLOADING_CERTIFICATE, ResponseCode.SERVER_ERROR.getCode());
        }
        return resMap;
    }

    private String upload(String certFileName, String orgId, String batchId, String directory) {
        try {
            File file = FileUtils.getFile(directory + certFileName);
            HashMap<String, String> properties = new HashMap<>();
            properties.put(JsonKey.CONTAINER_NAME, certVar.getCONTAINER_NAME());
            properties.put(JsonKey.CLOUD_STORAGE_TYPE, certVar.getCloudStorageType());
            properties.put(JsonKey.CLOUD_UPLOAD_RETRY_COUNT, certVar.getCLOUD_UPLOAD_RETRY_COUNT());
            properties.put(JsonKey.AZURE_STORAGE_SECRET, certVar.getAzureStorageSecret());
            properties.put(JsonKey.AZURE_STORAGE_KEY, certVar.getAzureStorageKey());
            StorageParams storageParams = new StorageParams(properties);
            storageParams.init();
            StringBuilder stringBuilder = new StringBuilder();
            stringBuilder.append(" ");
            stringBuilder.setLength(0);
            if (StringUtils.isNotEmpty(orgId)) {
                stringBuilder.append(orgId + "/");
            }
            if (StringUtils.isNotEmpty(batchId)) {
                stringBuilder.append(batchId + "/");
            }
            if(StringUtils.isNotEmpty(certVar.getStoragePath()))
                stringBuilder.append(certVar.getStoragePath());
            logger.info("Path of " + stringBuilder.toString());
            return storageParams.upload(stringBuilder.toString(), file, false);
        } catch (Exception ex) {
            logger.info("CertificateGeneratorActor:upload: Exception occurred while uploading certificate.", ex);
        }
        return StringUtils.EMPTY;
    }

    private HashMap<String, String> populatePropertiesMap(Request request) {
        certVar.setCloudProperties((Map<String, Object>) ((Map) request.get(JsonKey.CERTIFICATE)).get(JsonKey.STORE));
        HashMap<String, String> properties = new HashMap<>();
        String orgId = (String) ((Map) request.get(JsonKey.CERTIFICATE)).get(JsonKey.ORG_ID);
        String tag = (String) ((Map) request.get(JsonKey.CERTIFICATE)).get(JsonKey.TAG);
        Map<String, Object> keysObject = (Map<String, Object>) ((Map) request.get(JsonKey.CERTIFICATE)).get(JsonKey.KEYS);
        if (MapUtils.isNotEmpty(keysObject)) {
            String keyId = (String) keysObject.get(JsonKey.ID);
            properties.put(JsonKey.KEY_ID, keyId);
            properties.put(JsonKey.SIGN_CREATOR, certVar.getSignCreator(orgId, keyId));
            properties.put(JsonKey.PUBLIC_KEY_URL, certVar.getPUBLIC_KEY_URL(orgId, keyId));
            logger.info("populatePropertiesMap: keys after".concat(keyId));
        }
        properties.put(JsonKey.ORG_ID, orgId);
        properties.put(JsonKey.TAG, tag);
        properties.put(JsonKey.CONTAINER_NAME, certVar.getCONTAINER_NAME());
        properties.put(JsonKey.DOMAIN_URL, certVar.getDOMAIN_URL());
        properties.put(JsonKey.BADGE_URL, certVar.getBADGE_URL(orgId, tag));
        properties.put(JsonKey.ISSUER_URL, certVar.getISSUER_URL(orgId));
        properties.put(JsonKey.CONTEXT, certVar.getCONTEXT());
        properties.put(JsonKey.VERIFICATION_TYPE, certVar.getVERIFICATION_TYPE());
        properties.put(JsonKey.ACCESS_CODE_LENGTH, certVar.getACCESS_CODE_LENGTH());
        properties.put(JsonKey.SIGN_URL, certVar.getEncSignUrl());
        properties.put(JsonKey.SIGN_VERIFY_URL, certVar.getEncSignVerifyUrl());
        properties.put(JsonKey.ENC_SERVICE_URL, certVar.getEncryptionServiceUrl());
        properties.put(JsonKey.SIGNATORY_EXTENSION, certVar.getSignatoryExtensionUrl());
        properties.put(JsonKey.SLUG, certVar.getSlug());
        properties.put(JsonKey.CLOUD_STORAGE_TYPE, certVar.getCloudStorageType());
        properties.put(JsonKey.CLOUD_UPLOAD_RETRY_COUNT, certVar.getCLOUD_UPLOAD_RETRY_COUNT());
        properties.put(JsonKey.AZURE_STORAGE_SECRET, certVar.getAzureStorageSecret());
        properties.put(JsonKey.AZURE_STORAGE_KEY, certVar.getAzureStorageKey());
        properties.put(JsonKey.PATH, certVar.getStoragePath());

        logger.info("CertificateGeneratorActor:getProperties:properties got from Constant File ".concat(Collections.singleton(properties.toString()) + ""));
        return properties;
    }

    private String getDirectoryName(String orgId, String batchId, String zipFileName, Map<String, String> properties) {
        StringBuilder sb = new StringBuilder();
        if(checkStorageParamsExist(properties)) {
            sb.append("conf/");
            if (StringUtils.isNotEmpty(orgId)) {
                sb.append(orgId + "_");
            }
            if (StringUtils.isNotEmpty(batchId)) {
                sb.append(batchId + "_");
            }
        }  else   {
            // storage params are not present , save files in public folder
            sb.append("public/");
        }
        String dirName = sb.toString().concat(zipFileName.concat("/"));

        logger.info("getDirectoryName: " + dirName);
        return dirName;
    }

    /**
     * to check storage params are exist , if it is not exits save file in public assets folder
     * @param properties
     * @return
     */
    private Boolean checkStorageParamsExist(Map<String, String> properties)  {
        Boolean preview = true;
        // TODO: Fix - What happens if this AWS or something else?
        List<String> keys = Arrays.asList(JsonKey.CONTAINER_NAME, JsonKey.AZURE_STORAGE_KEY, JsonKey.AZURE_STORAGE_SECRET, JsonKey.CLOUD_STORAGE_TYPE);
        for (String key : keys) {
            if (StringUtils.isBlank(properties.get(key))) {
               return false;
            }
        }
        return preview;
    }
}

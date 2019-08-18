package org.sunbird.cert.actor;

import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.log4j.Logger;
import org.incredible.CertificateGenerator;
import org.incredible.certProcessor.CertModel;
import org.incredible.certProcessor.store.StorageParams;
import org.incredible.certProcessor.views.HTMLTempalteZip;
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
import java.net.URL;
import java.text.MessageFormat;
import java.util.*;

/**
 * This actor is responsible for certificate generation.
 *
 * @author manzarul
 */
@ActorConfig(
        tasks = {JsonKey.GENERATE_CERT,JsonKey.GET_SIGN_URL},
        asyncTasks = {}
)
public class CertificateGeneratorActor extends BaseActor {
    private Logger logger = Logger.getLogger(CertificateGeneratorActor.class);
    private static CertsConstant certVar = new CertsConstant();

    @Override
    public void onReceive(Request request) throws Throwable {
        String operation = request.getOperation();
        logger.info("onReceive method call start for operation " + operation);
        if (JsonKey.GENERATE_CERT.equalsIgnoreCase(operation)) {
            generateCertificate(request);
        }else if (CertActorOperation.GET_SIGN_URL.getOperation().equalsIgnoreCase(operation)) {
        	generateSignUrl (request) ;
        }
        logger.info("onReceive method call End");
    }

	private void generateSignUrl(Request request) {
		String uri = (String) request.getRequest().get(JsonKey.PDF_URL);
		logger.info("generate sign url method called for uri ");
		IStorageService storageService = getStorageService(certVar.getCloudStorageType());
		String signUrl = storageService.getSignedURL(certVar.getCONTAINER_NAME(), uri, Some.apply(getTimeoutInSeconds()),
				Some.apply("r"));
		Response response = new Response();
		response.put(JsonKey.RESPONSE, JsonKey.SUCCESS);
		response.put(JsonKey.SIGNED_URL, signUrl);
		sender().tell(response, self());
	}

    private static IStorageService getStorageService(String storageType) {
        String storageKey = CertsConstant.AZURE_STORAGE_KEY;
        String storageSecret = CertsConstant.AZURE_STORAGE_SECRET;
        return getStorageService(storageType, storageKey, storageSecret);
      }
    
    private static IStorageService getStorageService(
			String storageType, String storageKey, String storageSecret) {
		StorageConfig storageConfig = new StorageConfig(storageType, storageKey, storageSecret);
		IStorageService storageService = StorageServiceFactory.getStorageService(storageConfig);
		return storageService;
	}
    
    private static int getTimeoutInSeconds() {
        String timeoutInSecondsStr = CertsConstant.getExpiryLink(CertsConstant.DOWNLOAD_LINK_EXPIRY_TIMEOUT);
        return Integer.parseInt(timeoutInSecondsStr);
      }
    
	private void generateCertificate(Request request) throws BaseException {
        logger.info("Request received==" + request.getRequest());
        CertMapper certMapper = new CertMapper(populatePropertiesMap(request));
        List<CertModel> certModelList = certMapper.toList(request.getRequest());
        CertificateGenerator certificateGenerator = new CertificateGenerator(populatePropertiesMap(request));
        HTMLTempalteZip htmlTempalteZip = null;
        String directory;
        String url = (String) ((Map<String, Object>) request.getRequest().get(JsonKey.CERTIFICATE)).get(JsonKey.HTML_TEMPLATE);
        try {
            htmlTempalteZip = new HTMLTempalteZip(new URL(url));
            logger.info("CertificateGeneratorActor:generateCertificate:html zip generated");
        } catch (Exception ex) {
            logger.error("CertificateGeneratorActor:generateCertificate:Exception Occurred while creating HtmlTemplate provider.", ex);
            throw new BaseException("INVALID_PARAM_VALUE", MessageFormat.format(IResponseMessage.INVALID_PARAM_VALUE, url, JsonKey.HTML_TEMPLATE), ResponseCode.CLIENT_ERROR.getCode());
        }
        String orgId = (String) ((Map) request.get(JsonKey.CERTIFICATE)).get(JsonKey.ORG_ID);
        String tag = (String) ((Map) request.get(JsonKey.CERTIFICATE)).get(JsonKey.TAG);
        directory = "conf/" + orgId.concat("_") + tag.concat("_") + htmlTempalteZip.getZipFileName().concat("/");
        List<Map<String, String>> certUrlList = new ArrayList<>();
        for (CertModel certModel : certModelList) {
            String certUUID = "";
            try {
                certUUID = certificateGenerator.createCertificate(certModel, htmlTempalteZip, (String) ((Map<String, Object>) ((Map<String, Object>) request.getRequest().get(JsonKey.CERTIFICATE)).get(JsonKey.KEYS)).get(JsonKey.ID));
            } catch (Exception ex) {
                cleanup(directory, certUUID);
                logger.error("CertificateGeneratorActor:generateCertificate:Exception Occurred while generating certificate. : " + ex.getMessage());
                throw new BaseException("INTERNAL_SERVER_ERROR", IResponseMessage.INTERNAL_ERROR, ResponseCode.SERVER_ERROR.getCode());
            }
            certUrlList.add(uploadCertificate(certUUID, certModel.getIdentifier(), orgId, tag, directory));
            cleanup(directory, certUUID);
        }
        Response response = new Response();
        response.getResult().put("response", certUrlList);
        sender().tell(response, getSelf());
        logger.info("onReceive method call End");
    }

    private void cleanup(String path, String fileName) {
        try {
            File directory = new File(path);
            File[] files = directory.listFiles();
            for (File file : files) {
                if (file.getName().startsWith(fileName)) file.delete();
            }
            File file = new File(path);
            file.delete();
        } catch (Exception ex) {
            logger.error(ex.getMessage(), ex);
        }
    }

    private Map<String, String> uploadCertificate(String certUUID, String recipientID, String orgId, String batchId, String directory) throws BaseException {
        Map<String, String> resMap = new HashMap<>();
        String certFileName = certUUID + ".pdf";
        resMap.put(JsonKey.PDF_URL, upload(certFileName, orgId, batchId, directory));
        certFileName = certUUID + ".json";
        resMap.put(JsonKey.JSON_URL, upload(certFileName, orgId, batchId, directory));
        resMap.put(JsonKey.UNIQUE_ID, certUUID);
        resMap.put(JsonKey.RECIPIENT_ID, recipientID);
        if(StringUtils.isBlank(resMap.get(JsonKey.PDF_URL)) || StringUtils.isBlank(resMap.get(JsonKey.JSON_URL))){
            logger.error("CertificateGeneratorActor:uploadCertificate:Exception Occurred while uploading certificate pdfUrl and jsonUrl is null");
            throw new BaseException("INTERNAL_SERVER_ERROR", IResponseMessage.ERROR_UPLOADING_CERTIFICATE, ResponseCode.SERVER_ERROR.getCode());
        }
        return resMap;
    }

    private String upload(String certFileName, String orgId, String batchId, String directory) {
        try {
            //TODO  Un comment this to use cloud storage jar to upload file to azure as of now
            // not using because of jar conflict issue

            //HashMap<String,String> properties = new HashMap<>();
            //properties.put(JsonKey.CONTAINER_NAME,System.getenv(JsonKey.CONTAINER_NAME));
            //properties.put(JsonKey.CLOUD_STORAGE_TYPE,System.getenv(JsonKey.CLOUD_STORAGE_TYPE));
            //properties.put(JsonKey.CLOUD_UPLOAD_RETRY_COUNT,System.getenv(JsonKey.CLOUD_UPLOAD_RETRY_COUNT));
            //properties.put(JsonKey.AZURE_STORAGE_SECRET,System.getenv(JsonKey.AZURE_STORAGE_SECRET));
            //properties.put(JsonKey.AZURE_STORAGE_KEY,System.getenv(JsonKey.AZURE_STORAGE_KEY));

            //StorageParams storageParams = new StorageParams(properties);
            //storageParams.init();
            //return storageParams.upload(System.getenv(JsonKey.CONTAINER_NAME), "/", file, false);

            File file = FileUtils.getFile(directory + certFileName);
            HashMap<String,String> properties = new HashMap<>();
            properties.put(JsonKey.CONTAINER_NAME,certVar.getCONTAINER_NAME());
            properties.put(JsonKey.CLOUD_STORAGE_TYPE,certVar.getCloudStorageType());
            properties.put(JsonKey.CLOUD_UPLOAD_RETRY_COUNT,certVar.getCLOUD_UPLOAD_RETRY_COUNT());
            properties.put(JsonKey.AZURE_STORAGE_SECRET,certVar.getAzureStorageSecret());
            properties.put(JsonKey.AZURE_STORAGE_KEY,certVar.getAzureStorageKey());
            StorageParams storageParams = new StorageParams(properties);
            storageParams.init();
            return storageParams.upload(orgId + "/" + batchId+"/", file, false);
        } catch (Exception ex) {
            logger.info("CertificateGeneratorActor:upload: Exception occurred while uploading certificate.", ex);
        }
        return StringUtils.EMPTY;
    }

    private HashMap<String, String> populatePropertiesMap(Request request) {
        String orgId = (String) ((Map) request.get(JsonKey.CERTIFICATE)).get(JsonKey.ORG_ID);
        String tag = (String) ((Map) request.get(JsonKey.CERTIFICATE)).get(JsonKey.TAG);
        String keyId = (String) ((Map<String, Object>) ((Map<String, Object>) request.getRequest().get(JsonKey.CERTIFICATE)).get(JsonKey.KEYS)).get(JsonKey.ID);
        HashMap<String, String> properties = new HashMap<>();
        properties.put(JsonKey.ORG_ID, orgId);
        properties.put(JsonKey.TAG, tag);
        properties.put(JsonKey.KEY_ID, keyId);
        properties.put(JsonKey.CONTAINER_NAME, certVar.getCONTAINER_NAME());
        properties.put(JsonKey.DOMAIN_URL, certVar.getDOMAIN_URL());
        properties.put(JsonKey.BADGE_URL, certVar.getBADGE_URL(orgId, tag));
        properties.put(JsonKey.ISSUER_URL, certVar.getISSUER_URL(orgId));
        properties.put(JsonKey.CONTEXT, certVar.getCONTEXT());
        properties.put(JsonKey.VERIFICATION_TYPE, certVar.getVERIFICATION_TYPE());
        properties.put(JsonKey.ACCESS_CODE_LENGTH, certVar.getACCESS_CODE_LENGTH());
        properties.put(JsonKey.PUBLIC_KEY_URL, certVar.getPUBLIC_KEY_URL(orgId));
        properties.put(JsonKey.SIGN_CREATOR, certVar.getSignCreator(keyId));
        properties.put(JsonKey.SIGN_URL, certVar.getEncSignUrl());
        properties.put(JsonKey.SIGN_VERIFY_URL, certVar.getEncSignVerifyUrl());
        properties.put(JsonKey.ENC_SERVICE_URL, certVar.getEncryptionServiceUrl());

        logger.info("CertificateGeneratorActor:getProperties:properties got from Constant File ".concat(Collections.singleton(properties.toString()) + ""));
        return properties;
    }

 }
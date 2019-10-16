package controllers.certs;

import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.collections.MapUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.validator.UrlValidator;
import org.sunbird.BaseException;
import org.sunbird.message.IResponseMessage;
import org.sunbird.message.ResponseCode;
import org.sunbird.request.Request;
import org.sunbird.JsonKey;

import java.net.URI;
import java.net.URISyntaxException;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * This class contains method to validate certificate api request
 */
public class CertValidator {

    private static List<String> publicKeys;

    private static final String TAG_REGX = "[$&+,\\s:;=?@#|/'<>.^*()%!-]";

    /**
     * This method will validate generate certificate request
     *
     * @param request
     * @throws BaseException
     */
    public static void validateGenerateCertRequest(Request request) throws BaseException {

        Map<String, Object> certReq = (Map<String, Object>) request.getRequest().get(JsonKey.CERTIFICATE);
        checkMandatoryParamsPresent(certReq, JsonKey.CERTIFICATE, Arrays.asList(JsonKey.COURSE_NAME, JsonKey.NAME, JsonKey.HTML_TEMPLATE));
        validateCertData((List<Map<String, Object>>) certReq.get(JsonKey.DATA));
        validateCertIssuer((Map<String, Object>) certReq.get(JsonKey.ISSUER));
        validateCertSignatoryList((List<Map<String, Object>>) certReq.get(JsonKey.SIGNATORY_LIST));
        validateTagId((String) certReq.get(JsonKey.TAG));
        String basePath = (String) certReq.get(JsonKey.BASE_PATH);
        if(StringUtils.isNotBlank(basePath))    {
            validateBasePath(basePath);
        }
        if (certReq.containsKey(JsonKey.STORE)) {
            validateStore((Map<String, Object>) certReq.get(JsonKey.STORE));
        }
        if (certReq.containsKey(JsonKey.KEYS)) {
            validateKeys((Map<String, Object>) certReq.get(JsonKey.KEYS));
        }
    }

    private static void validateCertSignatoryList(List<Map<String, Object>> signatoryList) throws BaseException {
        checkMandatoryParamsPresent(signatoryList, JsonKey.CERTIFICATE + "." + JsonKey.SIGNATORY_LIST, Arrays.asList(JsonKey.NAME, JsonKey.ID, JsonKey.DESIGNATION, JsonKey.SIGNATORY_IMAGE));
    }

    private static void validateCertIssuer(Map<String, Object> issuer) throws BaseException {
        checkMandatoryParamsPresent(issuer, JsonKey.CERTIFICATE + "." + JsonKey.ISSUER, Arrays.asList(JsonKey.NAME, JsonKey.URL));
        publicKeys = (List<String>) issuer.get(JsonKey.PUBLIC_KEY);
    }

    private static void validateCertData(List<Map<String, Object>> data) throws BaseException {
        checkMandatoryParamsPresent(data, JsonKey.CERTIFICATE + "." + JsonKey.DATA, Arrays.asList(JsonKey.RECIPIENT_NAME));
    }

    private static void validateKeys(Map<String, Object> keys) throws BaseException {
        checkMandatoryParamsPresent(keys, JsonKey.CERTIFICATE + "." + JsonKey.KEYS, Arrays.asList(JsonKey.ID));
        validateIssuerPublicKeys(keys);
    }

    /**
     * this method used to validate public keys of Issuer object ,if  public key list is present, list  must contain keys.id value of
     * certificate request
     *
     * @param keys
     * @throws BaseException
     */
    private static void validateIssuerPublicKeys(Map<String, Object> keys) throws BaseException {
        List<String> keyIds = new ArrayList<>();
        if (CollectionUtils.isNotEmpty(publicKeys)) {
            publicKeys.forEach((publicKey) -> {
                if (publicKey.startsWith("http")) {
                    keyIds.add(getKeyFromPublicKeyUrl(publicKey));
                } else {
                    keyIds.add(publicKey);
                }
            });
        }
        if (!keyIds.contains(keys.get(JsonKey.ID))) {
            throw new BaseException("INVALID_PARAM_VALUE", MessageFormat.format(IResponseMessage.INVALID_PARAM_VALUE,
                    publicKeys, JsonKey.CERTIFICATE + "." + JsonKey.ISSUER + "." + JsonKey.PUBLIC_KEY)
                    + " ,public key array must contain keys.id value",
                    ResponseCode.CLIENT_ERROR.getCode());
        }
    }

    /**
     * to get keyId from the publicKey url
     *
     * @param publicKey
     * @return
     */
    private static String getKeyFromPublicKeyUrl(String publicKey) {
        String idStr = null;
        try {
            URI uri = new URI(publicKey);
            String path = uri.getPath();
            idStr = path.substring(path.lastIndexOf('/') + 1);
            idStr = idStr.substring(0, 1);
        } catch (URISyntaxException e) {
        }
        return idStr;
    }

    private static void checkMandatoryParamsPresent(
            List<Map<String, Object>> data, String parentKey, List<String> keys) throws BaseException {
        if (CollectionUtils.isEmpty(data)) {
            throw new BaseException("MANDATORY_PARAMETER_MISSING",
                    MessageFormat.format(IResponseMessage.MANDATORY_PARAMETER_MISSING, parentKey),
                    ResponseCode.CLIENT_ERROR.getCode());
        }
        for (Map<String, Object> map : data) {
            checkChildrenMapMandatoryParams(map, keys, parentKey);
        }

    }

    private static void checkMandatoryParamsPresent(
            Map<String, Object> data, String parentKey, List<String> keys) throws BaseException {
        if (MapUtils.isEmpty(data)) {
            throw new BaseException("MANDATORY_PARAMETER_MISSING",
                    MessageFormat.format(IResponseMessage.MANDATORY_PARAMETER_MISSING, parentKey),
                    ResponseCode.CLIENT_ERROR.getCode());
        }
        checkChildrenMapMandatoryParams(data, keys, parentKey);
    }

    private static void checkChildrenMapMandatoryParams(Map<String, Object> data, List<String> keys, String parentKey) throws BaseException {

        for (String key : keys) {
            if (StringUtils.isBlank((String) data.get(key))) {
                throw new BaseException("MANDATORY_PARAMETER_MISSING",
                        MessageFormat.format(IResponseMessage.MANDATORY_PARAMETER_MISSING, parentKey + "." + key),
                        ResponseCode.CLIENT_ERROR.getCode());
            }
        }
    }

    private static void validateStore(Map<String, Object> store)  throws   BaseException{
        checkMandatoryParamsPresent(store, JsonKey.CERTIFICATE + "." + JsonKey.STORE, Arrays.asList(JsonKey.TYPE));
        validateStorageType(store, JsonKey.CERTIFICATE + "." + JsonKey.STORE);
        checkMandatoryParamsPresent((Map<String, Object>)store.get(store.get(JsonKey.TYPE)), JsonKey.CERTIFICATE + "." + JsonKey.STORE + "."
                + store.get(JsonKey.TYPE), Arrays.asList(JsonKey.containerName, JsonKey.ACCOUNT, JsonKey.key));
    }

    private static void validateStorageType(Map<String, Object> data, String parentKey) throws BaseException {
        if(!StorageType.get().contains(data.get(JsonKey.TYPE)))  {
            throw new BaseException("INVALID_PARAM_VALUE",
                    MessageFormat.format(IResponseMessage.INVALID_PARAM_VALUE, data.get(JsonKey.TYPE), parentKey + "." + JsonKey.TYPE),
                    ResponseCode.CLIENT_ERROR.getCode());
        }
    }

    private static void validateBasePath(String basePath) throws BaseException {
        UrlValidator urlValidator = new UrlValidator();
        boolean isValid = urlValidator.isValid(basePath);
        if (!isValid) {
            throw new BaseException("INVALID_PARAM_VALUE",
                    MessageFormat.format(IResponseMessage.INVALID_PARAM_VALUE, basePath, JsonKey.CERTIFICATE
                            + "." + JsonKey.BASE_PATH),
                    ResponseCode.CLIENT_ERROR.getCode());
        }
    }

    private static void validateTagId(String tag) throws BaseException {
        Pattern pattern = Pattern.compile(TAG_REGX);
        if (pattern.matcher(tag).find()) {
            throw new BaseException("INVALID_PARAM_VALUE", MessageFormat.format(IResponseMessage.INVALID_PARAM_VALUE
                    , tag, JsonKey.TAG), ResponseCode.CLIENT_ERROR.getCode());
        }

    }

}


package org.sunbird;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.collections.MapUtils;
import org.apache.commons.lang3.StringUtils;
import org.incredible.certProcessor.CertModel;
import org.incredible.pojos.SignatoryExtension;
import org.incredible.pojos.ob.Criteria;
import org.incredible.pojos.ob.Issuer;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class CertMapper {

    private Map<String, String> properties;

    public CertMapper(Map<String, String> properties) {
        this.properties = properties;
    }

    public List<CertModel> toList(Map<String, Object> request) {
        Map<String, Object> json = (Map<String, Object>) request.get(JsonKey.CERTIFICATE);
        List<Map<String, Object>> dataList = (List<Map<String, Object>>) json.get(JsonKey.DATA);
        Map<String, Object> issuerData = (Map<String, Object>) json.get(JsonKey.ISSUER);
        Issuer issuer = getIssuer(issuerData);
        List<String> publicKeys = validatePublicKeys((List<String>) issuerData.get(JsonKey.PUBLIC_KEY),
                (Map<String, Object>) json.get(JsonKey.KEYS));
        issuer.setPublicKey(publicKeys.toArray(new String[0]));
        SignatoryExtension[] signatoryArr = getSignatoryArray((List<Map<String, Object>>) json.get(JsonKey.SIGNATORY_LIST));
        Criteria criteria = getCriteria((Map<String, Object>) json.get(JsonKey.CRITERIA));
        List<CertModel> certList = dataList.stream().map(data -> getCertModel(data)).collect(Collectors.toList());
        certList.stream().forEach(cert -> {
            cert.setIssuer(issuer);
            cert.setSignatoryList(signatoryArr);
            cert.setCourseName((String) json.get(JsonKey.COURSE_NAME));
            cert.setCertificateDescription((String) json.get(JsonKey.DESCRIPTION));
            cert.setCertificateLogo((String) json.get(JsonKey.LOGO));
            cert.setCriteria(criteria);
            String issuedDate = (String) json.get(JsonKey.ISSUE_DATE);
            if (StringUtils.isBlank(issuedDate)) {
                cert.setIssuedDate(getCurrentDate());
            } else {
                cert.setIssuedDate((String) json.get(JsonKey.ISSUE_DATE));
            }
            cert.setCertificateName((String) json.get(JsonKey.CERTIFICATE_NAME));
        });
        return certList;
    }

    private String getCurrentDate() {
        DateTimeFormatter dtf = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'");
        LocalDateTime now = LocalDateTime.now();
        return dtf.format(now);
    }

    private SignatoryExtension[] getSignatoryArray(List<Map<String, Object>> signatoryList) {
        return signatoryList.stream().map(signatory ->
                getSignatory(signatory)).toArray(SignatoryExtension[]::new);
    }

    private SignatoryExtension getSignatory(Map<String, Object> signatory) {
        SignatoryExtension signatoryExt = new SignatoryExtension(properties.get(JsonKey.SIGNATORY_EXTENSION));
        signatoryExt.setIdentity((String) signatory.get(JsonKey.ID));
        signatoryExt.setDesignation((String) signatory.get(JsonKey.DESIGNATION));
        signatoryExt.setImage((String) signatory.get(JsonKey.SIGNATORY_IMAGE));

        return signatoryExt;
    }


    private Issuer getIssuer(Map<String, Object> issuerData) {
        Issuer issuer = new Issuer(properties.get(JsonKey.CONTEXT));
        issuer.setName((String) issuerData.get(JsonKey.NAME));
        issuer.setUrl((String) issuerData.get(JsonKey.URL));
        return issuer;
    }

    private Criteria getCriteria(Map<String, Object> criteriaData) {
        return new ObjectMapper().convertValue(criteriaData, Criteria.class);
    }

    private CertModel getCertModel(Map<String, Object> data) {
        CertModel certModel = new CertModel();
        certModel.setRecipientName((String) data.get(JsonKey.RECIPIENT_NAME));
        certModel.setRecipientEmail((String) data.get(JsonKey.RECIPIENT_EMAIl));
        certModel.setRecipientPhone((String) data.get(JsonKey.RECIPIENT_PHONE));
        certModel.setIdentifier((String) data.get(JsonKey.RECIPIENT_ID));
        certModel.setRecipientPhoto((String) data.get(JsonKey.RECIPIENT_PHOTO));
        certModel.setValidFrom((String) data.get(JsonKey.VALID_FROM));
        certModel.setExpiry((String) data.get(JsonKey.EXPIRY));
        return certModel;
    }

    private List<String> validatePublicKeys(List<String> publicKeys, Map<String, Object> keys) {
        if (CollectionUtils.isEmpty(publicKeys) && MapUtils.isNotEmpty(keys)) {
            publicKeys = new ArrayList<>();
            publicKeys.add((String) keys.get(JsonKey.ID));
        }
        List<String> validatedPublicKeys = new ArrayList<>();
        if (CollectionUtils.isNotEmpty(publicKeys)) {
            publicKeys.forEach((publicKey) -> {
                if (!publicKey.startsWith("http")) {
                    validatedPublicKeys.add(properties.get(JsonKey.BASE_PATH)
                            .concat("/") + JsonKey.KEYS.concat("/") + publicKey.concat("_publicKey.json"));
                } else {
                    validatedPublicKeys.add(publicKey);
                }
            });
        }
        return validatedPublicKeys;
    }
}

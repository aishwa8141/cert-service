package org.incredible.certProcessor.views;

import org.apache.commons.lang.StringUtils;
import org.incredible.pojos.CertificateExtension;

import java.net.URI;
import java.net.URISyntaxException;
import java.text.DateFormat;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class HTMLVarResolver {


    private CertificateExtension certificateExtension;

    public HTMLVarResolver(CertificateExtension certificateExtension) {
        this.certificateExtension = certificateExtension;
    }

    public String getRecipientName() {
        return certificateExtension.getRecipient().getName();
    }

    public String getRecipientId() {
        return certificateExtension.getRecipient().getIdentity();
    }

    public String getCourseName() {
        //todo need to resolve
        return certificateExtension.getBadge().getName();
    }


    public String getQrCodeImage() {
        try {
            URI uri = new URI(certificateExtension.getId());
            String path = uri.getPath();
            String idStr = path.substring(path.lastIndexOf('/') + 1);
            return StringUtils.substringBefore(idStr, ".") + ".png";
        } catch (URISyntaxException e) {
            return null;
        }
    }

    public String getIssuedDate() {
        SimpleDateFormat simpleDateFormat = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'");
        String dateInFormat;
        try {
            Date parsedIssuedDate = simpleDateFormat.parse(certificateExtension.getIssuedOn());
            DateFormat format = new SimpleDateFormat("dd MMMM yyy", Locale.getDefault());
            format.format(parsedIssuedDate);
            dateInFormat = format.format(parsedIssuedDate);
            return dateInFormat;
        } catch (ParseException e) {
            return null;
        }
    }

    public String getSignatory0Image() {
        return certificateExtension.getSignatory()[0].getImage();
    }

    public String getSignatory0Designation() {
        return certificateExtension.getSignatory()[0].getDesignation();
    }

    public String getSignatory1Image() {
        return certificateExtension.getSignatory()[1].getImage();
    }

    public String getSignatory1Designation() {
        return certificateExtension.getSignatory()[1].getDesignation();
    }

    public String getCertificateName() {
        return certificateExtension.getBadge().getName();
    }

    public String getCertificateDescription() {
        return certificateExtension.getBadge().getDescription();
    }

    public String getExpiryDate() {
        return certificateExtension.getExpires();
    }

    public String getRecipientPhoto() {
        return  certificateExtension.getRecipient().getPhoto();
    }

}

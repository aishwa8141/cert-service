package org.incredible;

import org.apache.commons.lang3.StringUtils;
import org.apache.log4j.Logger;

import java.net.MalformedURLException;
import java.net.URL;

public class UrlManager {
    private static URL urlPath;
    private static Logger logger = Logger.getLogger(UrlManager.class);

    public static String getSharableUrl(String url,String containerName) {
        String uri;
        uri = removeQueryParams(url);
        uri=fetchFileFromUrl(uri);
        return removeContainerName(uri,containerName);
    }

    private static String removeQueryParams(String url) {
        return StringUtils.isNotBlank(url)?url.split("\\?")[0]:url;
    }

    private static String fetchFileFromUrl(String url) {
        try {
            urlPath = new URL(url);
            return urlPath.getFile();
        } catch (Exception e) {
            logger.error("UrlManager:getUriFromUrl:some error occurred in fetch fileName from Url:".concat(url));
            return StringUtils.EMPTY;
        }
    }
    private static String removeContainerName(String url,String containerName){
        String containerNameStr="/".concat(containerName).concat("/");
        logger.info("UrlManager:removeContainerName:container string formed:".concat(containerNameStr));
        return url.replace(containerNameStr,"");
    }

    public static String getUri(String pdfUrl) throws MalformedURLException {
        if (pdfUrl.startsWith("http")) {
            String uri = StringUtils.substringAfter(new URL(pdfUrl).getPath(), "/");
            String[] path = uri.split("/");
            return StringUtils.join(path, "/", path.length - 2, path.length);
        } else {
            return pdfUrl;
        }
    }
}

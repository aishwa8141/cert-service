package org.incredible.certProcessor.views;

import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.http.NameValuePair;
import org.apache.http.client.utils.URLEncodedUtils;
import org.incredible.certProcessor.JsonKey;
import org.incredible.certProcessor.store.ICertStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.sunbird.cloud.storage.exception.StorageServiceException;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Downloads zip file and unzips from given cloud(container) based relative url or from the public http url
 */
public class HTMLTemplateZip extends HTMLTemplateProvider {


    private String content = null;

    private static Logger logger = LoggerFactory.getLogger(HTMLTemplateZip.class);

    /**
     * html zip file url (relative path (uri) of container based url or pubic http url)
     */
    private String zipUrl;

    private ICertStore htmlTemplateStore;

    private String zipFilePath = "conf/";

    public HTMLTemplateZip(ICertStore htmlTemplateStore, String zipUrl) {
        this.htmlTemplateStore = htmlTemplateStore;
        this.zipUrl = zipUrl;

    }

    /**
     * unzips zip file
     *
     * @param zipFile
     * @param destDir
     */
    private void unzip(String zipFile, String destDir) {
        File dir = new File(destDir);
        // create output directory if it doesn't exist
        if (!dir.exists()) {
            dir.mkdirs();
        }
        FileInputStream fis;
        try {
            fis = new FileInputStream(zipFile);
            ZipInputStream zipIn = new ZipInputStream(fis);
            ZipEntry entry = zipIn.getNextEntry();
            // iterates over entries in the zip file
            while (entry != null) {
                String filePath = destDir + File.separator + entry.getName();
                if (!entry.isDirectory()) {
                    // if the entry is a file, extracts it
                    extractFile(zipIn, filePath);
                } else {
                    // if the entry is a directory, make the directory
                    File subDir = new File(filePath);
                    subDir.mkdir();
                }
                zipIn.closeEntry();
                entry = zipIn.getNextEntry();
            }
            zipIn.close();
            fis.close();
            logger.info("Unzipping zip file is finished");
        } catch (IOException e) {
            logger.debug("Exception while unzip file {}", e.getMessage());
        }

    }

    /**
     * extracts each files in zip file (zip entry)
     *
     * @param zipIn
     * @param filePath
     * @throws IOException
     */
    private void extractFile(ZipInputStream zipIn, String filePath) throws IOException {
        BufferedOutputStream bos = new BufferedOutputStream(new FileOutputStream(filePath));
        byte[] bytesIn = new byte[4096];
        int read = 0;
        while ((read = zipIn.read(bytesIn)) != -1) {
            bos.write(bytesIn, 0, read);
        }
        bos.close();
    }


    /**
     * used to get file name from the url
     *
     * @return zip file name
     */
    public String getZipFileName() {
        String fileName = null;
        try {
            URI uri = new URI(zipUrl);
            String path = uri.getPath();
            fileName = path.substring(path.lastIndexOf('/') + 1);
            if(StringUtils.isNotBlank(uri.getQuery())) {
                logger.info("zip url query {}", uri.getQuery());
                List<NameValuePair> params = URLEncodedUtils.parse(new URI(zipUrl), "UTF-8");
                for (NameValuePair param : params) {
                    if(StringUtils.equals(JsonKey.ID, param.getName())){
                        fileName = param.getValue();
                    }
                }
            }
            if (!fileName.endsWith(".zip"))
                return fileName.concat(".zip");
        } catch (URISyntaxException e) {
            logger.debug("Exception while getting key id from the sign-creator url : {}", e.getMessage());
        }
        return fileName;
    }

    private void readIndexHtmlFile(String absolutePath) throws IOException {
        String htmlFileName = "/index.html";
        if (!isFileExists(new File(absolutePath + htmlFileName))) {
            unzip(zipFilePath + getZipFileName(), absolutePath);
        }
        FileInputStream fis = new FileInputStream(absolutePath + htmlFileName);
        content = IOUtils.toString(fis, StandardCharsets.UTF_8);
        fis.close();

    }

    private void downloadAndUnzip(String zipFileName, String targetDirectory) throws IOException, StorageServiceException {
        htmlTemplateStore.init();
        htmlTemplateStore.get(zipUrl, zipFileName, zipFilePath);
        unzip(zipFilePath + zipFileName, targetDirectory);
    }

    /**
     * This method is used to get Html file content in string format
     *
     * @return html string
     */
    @Override
    public String getTemplateContent(String filePath) throws IOException, StorageServiceException {
        String zipFileName = getZipFileName();
        if (content == null) {
            File targetDirectory = new File(filePath);
            if (isFileExists(new File(zipFilePath + zipFileName))) {
                readIndexHtmlFile(targetDirectory.getAbsolutePath());
            } else {
                downloadAndUnzip(zipFileName, targetDirectory.getAbsolutePath());
                readIndexHtmlFile(targetDirectory.getAbsolutePath());
            }
        }
        return content;
    }

}
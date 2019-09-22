package org.incredible.certProcessor.views;

import org.apache.commons.io.IOUtils;
import org.incredible.certProcessor.store.ICertStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
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
            unzip("conf/" + getZipFileName(), absolutePath);
        }
        FileInputStream fis = new FileInputStream(absolutePath + htmlFileName);
        content = IOUtils.toString(fis, StandardCharsets.UTF_8);
        fis.close();

    }

    private void download(String zipFileName, String targetDirectory) throws IOException {
        htmlTemplateStore.init();
        htmlTemplateStore.get(zipUrl, zipFileName, "conf/");
        unzip("conf/" + zipFileName, targetDirectory);
        readIndexHtmlFile(targetDirectory);
    }

    /**
     * This method is used to get Html file content in string format
     *
     * @return html string
     */
    @Override
    public String getTemplateContent(String filePath) throws IOException {
        String zipFileName = getZipFileName();
        if (content == null) {
            File targetDirectory = new File(filePath);
            if (isFileExists(new File("conf/" + zipFileName))) {
                readIndexHtmlFile(targetDirectory.getAbsolutePath());
            } else {
                download(zipFileName, targetDirectory.getAbsolutePath());
            }
        }
        return content;
    }

}
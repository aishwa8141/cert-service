package org.incredible.certProcessor.views;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

public class PhantomTest {
    private static Logger logger = LoggerFactory.getLogger(PhantomTest.class);


    private static final String jsFilePath = "service/conf/rasterize.js";
    private static final String phantomJsFilePath = "/Users/aishwarya/Downloads/phantomjs-2.1.1-macosx/bin/phantomjs";
    private static final String outPutFile = "indexlatest-withzoom39-1.pdf";

    public static void main(String args[]) {

        try {
            new PhantomTest().convertHtmlToPdf("/Users/aishwarya/workspace/cert-service/service/conf/0125450863553740809_certificate/index.html", null, null, outPutFile);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public void convertHtmlToPdf(String htmlFilePath,String jsFilePat,String phantomJsPath,String pdfReportPath) throws Exception {
        //load html file
        //load js file that will handle render html and convert to pdf
        File htmlFile = new File(htmlFilePath);

        File configFile = new File(jsFilePath);
        // tmp pdf file for output
        File pdfReport = new File(pdfReportPath);

        logger.info("pdf path " + pdfReport.getAbsolutePath());
        logger.info("html "+ htmlFile.getAbsolutePath());
        ProcessBuilder renderProcess = new ProcessBuilder(phantomJsFilePath, configFile.getAbsolutePath(),
                htmlFile.getAbsolutePath(), pdfReport.getAbsolutePath());
        Process phantom = renderProcess.start();
        // you need to read phantom.getInputStream() and phantom.getErrorStream()
        // otherwise if they output something the process won't end
        boolean exitCode = phantom.waitFor(10, TimeUnit.SECONDS);
        if (!exitCode) {
            logger.info("Not able to generate reports.");
            System.out.println(convertInputStreamToString(phantom.getErrorStream()));
            phantom.destroyForcibly();

        }else {
            logger.info("Pdf generated: " + pdfReport.getAbsolutePath());
            phantom.destroy();
        }
    }



    private static String convertInputStreamToString(InputStream inputStream)
            throws IOException {
        ByteArrayOutputStream result = new ByteArrayOutputStream();
        byte[] buffer = new byte[1024];
        int length;
        while ((length = inputStream.read(buffer)) != -1) {
            result.write(buffer, 0, length);
        }
        inputStream.close();
        return result.toString(StandardCharsets.UTF_8.name());
    }
}
package com.itdhq.poc.ocrsign;

import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import org.alfresco.repo.policy.Behaviour;
import org.alfresco.repo.policy.JavaBehaviour;
import org.alfresco.repo.policy.PolicyComponent;
import org.apache.log4j.Logger;

import java.util.*;
import net.sourceforge.tess4j.Tesseract;
import org.alfresco.model.ContentModel;
import org.alfresco.repo.content.MimetypeMap;
import org.alfresco.repo.node.NodeServicePolicies;
import org.alfresco.service.cmr.repository.ChildAssociationRef;
import org.alfresco.service.cmr.repository.ContentReader;
import org.alfresco.service.cmr.repository.ContentService;
import org.alfresco.service.cmr.repository.NodeRef;
import org.alfresco.service.cmr.repository.NodeService;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentInformation;
import org.apache.pdfbox.pdmodel.PDPage;
//import org.apache.pdfbox.rendering.PDFRenderer;


public class OcrSignPolicy
        implements NodeServicePolicies.OnCreateNodePolicy
{
    private Logger logger = Logger.getLogger(OcrSignPolicy.class);
    private PolicyComponent policyComponent;
    private NodeService nodeService;
    private ContentService contentService;
    private String tessdataPath;
    private String targetFileNamePrefix;
    
    public void setPolicyComponent(PolicyComponent policyComponent) {
        this.policyComponent = policyComponent;
    }
    
    public void setNodeService(NodeService nodeService) {
        this.nodeService = nodeService;
    }
    
    public void setContentService(ContentService contentService) {
        this.contentService = contentService;
    }
    
    public void setTessdataPath(String tessdataPath) {
        this.tessdataPath = tessdataPath;
    }
    
    public void setTargetFileNamePrefix(String targetFileNamePrefix) {
        this.targetFileNamePrefix = targetFileNamePrefix;
    }
    
    public void init() {
        this.policyComponent.bindClassBehaviour(
                NodeServicePolicies.OnCreateNodePolicy.QNAME,
                this,
                new JavaBehaviour(this, "onCreateNode", Behaviour.NotificationFrequency.TRANSACTION_COMMIT));
    }

    @Override
    public void onCreateNode(ChildAssociationRef childAssocRef) {
        try {
            NodeRef nodeRef = childAssocRef.getChildRef();
            // WA: skip ghost nodes
            if(!nodeService.exists(nodeRef))
                return;
            ContentReader reader = contentService.getReader(nodeRef, ContentModel.PROP_CONTENT);
            // WA: skip empty nodes
            if(reader == null)
                return;
            // WA: handle only pdf
            String mime = reader.getMimetype();
            if(!MimetypeMap.MIMETYPE_PDF.equalsIgnoreCase(mime))
                return;
            // WA: filter by name - we do not want to process not our documents
            String name = (String)nodeService.getProperty(nodeRef, ContentModel.PROP_NAME);
            if(!name.startsWith(targetFileNamePrefix))
                return;
            
            InputStream originalInputStream = reader.getContentInputStream();
            PDDocument document = PDDocument.load(originalInputStream);
            List<PDPage> pages =  document.getDocumentCatalog().getAllPages();
            // TODO
            BufferedImage image = pages.get(0).convertToImage(BufferedImage.TYPE_INT_RGB, 600);

            //OCR stuff with prepared image
            Tesseract instance = new Tesseract();
            instance.setDatapath(tessdataPath);
            instance.setLanguage("eng");
            String result1 = instance.doOCR(image);
            //String result2 = instance.doOCR(image,new Rectangle(3202,2039,1280,106));

            ArrayList<String> keys = new ArrayList<String>();
            keys.add("Due");
            keys.add("From");
            keys.add("To");
            keys.add("Total");

            HashMap<String, String> obtainedData = extractData(result1, keys);
            //obtainedData.put("Total", result2.substring(result2.lastIndexOf(":")));
            
            String desc = "";
            PDDocumentInformation metadata = document.getDocumentInformation();
            for(String k : obtainedData.keySet())
                desc += k + "=" + obtainedData.get(k) + "; ";
            
            nodeService.setProperty(nodeRef, ContentModel.PROP_DESCRIPTION, desc);
            
            //String[] arguments = {"/home/argentum/keystore.p12", "123456", outputFile.getCanonicalPath()};
            //CreateSignature.main(arguments);

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    protected HashMap<String, String> extractData(String source, ArrayList<String> keys) {
        HashMap<String, String> data = new HashMap<>();
        String temp;
        String tempsource = source;
        String tempvalue;
        do {
            temp = tempsource.substring(0,tempsource.indexOf("\n"));
            for (String key : keys) {
                if (temp.contains(key + ":")) {
                    tempvalue = temp.substring(temp.indexOf(":") + 2, temp.length()); //2 is a lazy way to remove a 1 space symbol :D
                    data.put(key, tempvalue);
                    System.out.println("Extracted " + key + " = " + tempvalue);
                }
            }
            tempsource = tempsource.substring(tempsource.indexOf("\n")+1);
        } while (tempsource.length() > 0);

        return data;
    }
}

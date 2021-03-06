package dk.statsbiblioteket.doms.ingesters.radiotv;

import dk.statsbiblioteket.doms.central.InvalidCredentialsException;
import dk.statsbiblioteket.doms.central.InvalidResourceException;
import dk.statsbiblioteket.doms.central.MethodFailedException;
import dk.statsbiblioteket.doms.client.DomsWSClient;
import dk.statsbiblioteket.doms.client.exceptions.NoObjectFound;
import dk.statsbiblioteket.doms.client.exceptions.NotFoundException;
import dk.statsbiblioteket.doms.client.exceptions.ServerOperationFailed;
import dk.statsbiblioteket.doms.client.exceptions.XMLParseException;
import dk.statsbiblioteket.doms.client.impl.objects.AbstractDigitalObjectFactory;
import dk.statsbiblioteket.doms.client.objects.DigitalObject;
import dk.statsbiblioteket.doms.client.relations.LiteralRelation;
import dk.statsbiblioteket.doms.client.relations.ObjectRelation;
import dk.statsbiblioteket.doms.client.relations.Relation;
import dk.statsbiblioteket.doms.client.relations.RelationDeclaration;
import dk.statsbiblioteket.util.xml.DOM;
import dk.statsbiblioteket.util.xml.XPathSelector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xmlunit.builder.DiffBuilder;
import org.xmlunit.builder.Input;
import org.xmlunit.diff.Comparison;
import org.xmlunit.diff.ComparisonResult;
import org.xmlunit.diff.ComparisonType;
import org.xmlunit.diff.Diff;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.Source;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.stream.Collectors;

/**
 * Code to create programs.
 */
public class RecordCreator {

    public static final String DC_NAMESPACE = "http://purl.org/dc/elements/1.1/";
    public static final String PBCORE_DESCRIPTION_ELEMENT = "//program/pbcore/pbc:PBCoreDescriptionDocument";
    public static final String PROGRAM_TEMPLATE_PID = "doms:Template_Program";
    public static final String PROGRAM_PBCORE_DS_ID = "PBCORE";
    public static final String RITZAU_ORIGINAL_DS_ID = "RITZAU_ORIGINAL";
    public static final String GALLUP_ORIGINAL_DS_ID = "GALLUP_ORIGINAL";
    public static final String PROGRAM_BROADCAST_DS_ID = "PROGRAM_BROADCAST";
    public static final String DC_DS_ID = "DC";
    public static final String HAS_FILE_RELATION = "http://doms.statsbiblioteket.dk/relations/default/0/1/#hasFile";

    private final Logger log = LoggerFactory.getLogger(getClass());
    private final DomsWSClient domsClient;
    private final boolean overwrite;
    private final boolean check;
    private final DocumentBuilder documentBuilder;
    private final XPathSelector xPathSelector;


    public RecordCreator(DomsWSClient domsClient, boolean overwrite, boolean check) {
        this.domsClient = domsClient;
        this.overwrite = overwrite;
        this.check = check;
        DocumentBuilderFactory documentBuilderFactory = DocumentBuilderFactory.newInstance();
        try {
            documentBuilder = documentBuilderFactory.newDocumentBuilder();
        } catch (ParserConfigurationException e) {
            throw new RuntimeException("Failed to understand xml...", e);
        }
        //This is not threadsafe so it must be created here, as we make a new RecordCreater for each file to ingest
        xPathSelector = DOM.createXPathSelector(
                "dc", DC_NAMESPACE,
                "pbc", "http://www.pbcore.org/PBCore/PBCoreNamespace.html",
                "ritzau", "http://doms.statsbiblioteket.dk/types/ritzau_original/0/1/#",
                "gallup", "http://doms.statsbiblioteket.dk/types/gallup_original/0/1/#",
                "pb", "http://doms.statsbiblioteket.dk/types/program_broadcast/0/1/#");
    }

    /**
     * Ingests or updates a program object.
     *
     * Synchronized on this object, not class, as the xpath system for reading the source file is not thread safe
     *
     *
     * @param radioTVMetadata Bibliographical metadata about the program.
     * @param filename the name of the source file. Is only used for logging and doms audit comments
     * @return PID of the newly created program object, created by the DOMS.
     *
     * @throws ServerOperationFailed    if creation or manipulation of the program object fails.
     * @throws XMLParseException        if any errors were encountered while processing the
     *                                  <code>radioTVMetadata</code> XML document.
     * @throws MalformedURLException if a file element contains an invalid URL.
     * @throws NoObjectFound         if a URL is referenced, which is not found in DOMS.
     */
    public synchronized String ingestProgram(Document radioTVMetadata, String filename) throws NoObjectFound, ServerOperationFailed, MalformedURLException, OverwriteException, XMLParseException {
        // Get pids of referenced files - do this first, to ensure fail-early in case of missing files.
        List<String> filePIDs = getFilePids(radioTVMetadata);
        log.debug("Found pids {} of referenced files",filePIDs);

        // Find or create program object.
        List<String> oldIdentifiers = getOldIdentifiers(radioTVMetadata);
        log.debug("Found these old identifiers {} in the program to ingest", oldIdentifiers);

        String programObjectPID = alreadyExistsInRepo(oldIdentifiers);
        if (programObjectPID != null){
            log.info("Program already exist in repo, with pid={}",programObjectPID);
            if (check) {
                log.info("Preparing to check semantic equivalence of pid={}",programObjectPID);
                if (checkSemanticIdentity(programObjectPID, radioTVMetadata, filePIDs)) {
                    //check if what is there is identical to what we want to write
                    log.info("Object pid={} is semantically identical, so no updates are performed.", programObjectPID);
                    return programObjectPID;
                } else {
                    log.info("Object pid={} is not semantically identical.", programObjectPID);
                }
            }
            if (overwrite){ //overwrite whatever is there
                log.info("Starting to overwrite contents of Program pid={}",programObjectPID);
                prepareProgramForOverwrite(programObjectPID, filename, oldIdentifiers);
            } else { //fail
                throw new OverwriteException("Found existing object pid='"+programObjectPID+"' and overwrite flag is false");
            }
        } else {
            log.debug("Old identifiers {} did not find a program object in doms, so creating a new one", oldIdentifiers);
            programObjectPID = createNewProgramObject(filename, oldIdentifiers);
            log.info("Created new program object with pid={}", programObjectPID);
        }

        //Set label as title
        setTitle(radioTVMetadata, filename, programObjectPID);

        //Add/update the datastreams
        String datastreamComment = Util.domsCommenter(filename, "updated datastream");
        addPBCore(radioTVMetadata, programObjectPID, datastreamComment);
        addRitzau(radioTVMetadata, programObjectPID, datastreamComment);
        addGallup(radioTVMetadata, programObjectPID, datastreamComment);
        addBroadcast(radioTVMetadata, programObjectPID, datastreamComment);

        //Set the relations to the data files
        setFileRelations(programObjectPID, filePIDs, filename);

        return programObjectPID;
    }

    private boolean checkSemanticIdentity(String programObjectPID, Document radioTVMetadata, List<String> filePIDs) {

        try {
            //Title
            String expectedTitle = getTitle(radioTVMetadata);
            String actualTitle = getDomsTitle(programObjectPID);
            boolean titleIdentical = expectedTitle.equals(actualTitle);
            if (!titleIdentical) {
                log.debug("Titles not identical, {} vs {}", expectedTitle, actualTitle);
            }

            //PBCore
            Document pbCoreExpected = createDocumentFromNode(radioTVMetadata, PBCORE_DESCRIPTION_ELEMENT);
            Document pbCoreActual = domsClient.getDataStream(programObjectPID, PROGRAM_PBCORE_DS_ID);
            boolean pbcoreIdentical = compareDocuments(pbCoreExpected, pbCoreActual, programObjectPID);

            //Ritzau
            Document ritzauExpected = createDocumentFromNode(radioTVMetadata,
                                                             "//program/originals/ritzau:ritzau_original");
            Document ritzauActual = domsClient.getDataStream(programObjectPID, RITZAU_ORIGINAL_DS_ID);
            boolean ritzauIdentical = compareDocuments(ritzauExpected, ritzauActual, programObjectPID);

            //Gallup
            Document gallupExpected = createDocumentFromNode(radioTVMetadata,
                                                             "//program/originals/gallup:gallup_original|//program/originals/gallup:tvmeterProgram");
            Document gallupActual = domsClient.getDataStream(programObjectPID, GALLUP_ORIGINAL_DS_ID);
            boolean gallupIdentical = compareDocuments(gallupExpected, gallupActual, programObjectPID);

            //Broadcast
            Document broadcastExpected = createDocumentFromNode(radioTVMetadata, "//program/pb:programBroadcast");
            Document broadcastActual = domsClient.getDataStream(programObjectPID, PROGRAM_BROADCAST_DS_ID);
            boolean broadcastIdentical = compareDocuments(broadcastExpected, broadcastActual, programObjectPID);

            //Relations
            boolean relationsIdentical = checkFileRelations(programObjectPID, filePIDs);

            log.debug("Identicals: title={}, pbcore={}, ritzau={}, gallup={}, broadcast={}, relations={}",
                      titleIdentical, pbcoreIdentical, ritzauIdentical, gallupIdentical, broadcastIdentical,
                      relationsIdentical);

            return titleIdentical && pbcoreIdentical && ritzauIdentical && gallupIdentical && broadcastIdentical &&
                   relationsIdentical;
        } catch (XMLParseException | ServerOperationFailed e) {
            log.warn("Caught exception while checking semantic identicality of pid={}, so the objects are regarded as not identical",programObjectPID, e);
            return false;
        }

    }

    private String getDomsTitle(String programObjectPID) throws ServerOperationFailed {
        //TODO This is so inefficient but I DO NOT WANT TO UPGRADE THE ENTIRE SYSTEM TO FIX IT
        DigitalObject digitalObject = domsClient.getDigitalObjectFactory().getDigitalObject(programObjectPID);
        return digitalObject.getTitle();
    }


    private boolean compareDocuments(Document expected, Document actual, String pid){
        Source control = Input.fromDocument(expected).build();
        Source test = Input.fromDocument(actual).build();

        Diff d = Util.xmlDiff(control,test);

        if (!d.hasDifferences()) {
            return true;
        } else {
            log.debug("Differences from object pid={}. Differences='{}' ", pid, d.toString());
            return false;
        }
    }


    private void setFileRelations(String programObjectPID, List<String> filePIDs, String filename) throws ServerOperationFailed, XMLParseException {
        List<Relation> relations = domsClient.listObjectRelations(programObjectPID, HAS_FILE_RELATION);
        HashSet<String> existingRels = new HashSet<String>();
        for (Relation relation : relations) {
            if (relation instanceof ObjectRelation) {
                LiteralRelation relationWrapper = wrapAsLiteral((ObjectRelation) relation);
                String predicate = relationWrapper.getPredicate(); //This should be HAS_FILE_RELATION
                String objectPid = relationWrapper.getObject(); //This should be prograObjectPid
                String subjectPid = relationWrapper.getSubjectPid();
                log.debug("Found relation {},'{}',{}", objectPid, predicate, subjectPid);
                if (!filePIDs.contains(subjectPid)) {
                    log.debug("Removing relation relation {},'{}',{}", objectPid, predicate, subjectPid);
                    String comment = Util.domsCommenter(filename, "removed relation '{0}' to '{1}'",
                                                        predicate,
                                                        subjectPid);
                    domsClient.removeObjectRelation(relationWrapper, comment);
                } else {
                    existingRels.add(subjectPid);
                }
            }
        }
        for (String filePID : filePIDs) {
            if (!existingRels.contains(filePID)) {
                log.debug("Adding relation {},'{}',{}", programObjectPID, HAS_FILE_RELATION, filePID);
                String comment = Util.domsCommenter(filename, "added relation '{0}' to '{1}'",
                                                    HAS_FILE_RELATION, filePID);
                domsClient.addObjectRelation(programObjectPID, HAS_FILE_RELATION, filePID, comment);

            }
        }
    }


    private boolean checkFileRelations(String programObjectPID, List<String> filePIDs) throws ServerOperationFailed, XMLParseException {
        boolean identical = true;

        List<Relation> relations = domsClient.listObjectRelations(programObjectPID, HAS_FILE_RELATION);

        HashSet<String> existingRels = new HashSet<String>();
        for (Relation relation : relations) {
            if (relation instanceof ObjectRelation) {
                LiteralRelation relationWrapper = wrapAsLiteral((ObjectRelation) relation);
                String predicate = relationWrapper.getPredicate(); //This should be HAS_FILE_RELATION
                String objectPid = relationWrapper.getObject(); //This should be prograObjectPid
                String subjectPid = relationWrapper.getSubjectPid();
                log.debug("Found relation {},'{}',{}", objectPid, predicate, subjectPid);
                if (!filePIDs.contains(subjectPid)) {
                    log.debug("Found extranous relation {},'{}',{}", objectPid, predicate, subjectPid);
                    identical = false;
                } else {
                    existingRels.add(subjectPid);
                }
            }
        }
        for (String filePID : filePIDs) {
            if (!existingRels.contains(filePID)) {
                log.debug("Missing relation {},'{}',{}", programObjectPID, HAS_FILE_RELATION, filePID);
                identical = false;
            }
        }
        return identical;
    }


    private LiteralRelation wrapAsLiteral(final ObjectRelation relation) {
        return new LiteralRelation() {

            @Override
            public String getObject() {
                return relation.getSubjectPid();
            }

            public DigitalObject getSubject() throws ServerOperationFailed {
                return relation.getObject();
            }

            public String getSubjectPid() {
                return relation.getObjectPid();
            }

            public String getPredicate() {
                return relation.getPredicate();
            }

            public void remove() throws ServerOperationFailed {
                relation.remove();
            }

            public Set<RelationDeclaration> getDeclarations() throws ServerOperationFailed {
                return relation.getDeclarations();
            }
        };
    }

    private void addBroadcast(Document radioTVMetadata, String objectPID, String comment) throws ServerOperationFailed {
        // Add the program broadcast datastream
        log.debug("Adding/Updating {} datastream", PROGRAM_BROADCAST_DS_ID);
        Document programBroadcastDocument = createDocumentFromNode(radioTVMetadata, "//program/pb:programBroadcast");
        domsClient.updateDataStream(objectPID, PROGRAM_BROADCAST_DS_ID, programBroadcastDocument, comment);
    }

    private void addGallup(Document radioTVMetadata, String objectPID, String comment) throws ServerOperationFailed {
        // Add the Gallup datastream
        log.debug("Adding/Updating {} datastream", GALLUP_ORIGINAL_DS_ID);
        Document gallupOriginalDocument = createDocumentFromNode(radioTVMetadata,
                                                                 "//program/originals/gallup:gallup_original|//program/originals/gallup:tvmeterProgram");
        domsClient.updateDataStream(objectPID, GALLUP_ORIGINAL_DS_ID, gallupOriginalDocument, comment);
    }

    private void addRitzau(Document radioTVMetadata, String objectPID, String comment) throws ServerOperationFailed {
        // Add Ritzau datastream
        log.debug("Adding/Updating {} datastream", RITZAU_ORIGINAL_DS_ID);
        Document ritzauOriginalDocument = createDocumentFromNode(radioTVMetadata,
                                                                 "//program/originals/ritzau:ritzau_original");
        domsClient.updateDataStream(objectPID, RITZAU_ORIGINAL_DS_ID, ritzauOriginalDocument, comment);
    }

    private void addPBCore(Document radioTVMetadata, String objectPID, String comment) throws ServerOperationFailed {
        // Add PBCore datastream
        log.debug("Adding/Updating {} datastream", PROGRAM_PBCORE_DS_ID);
        Document pbCoreDataStreamDocument = createDocumentFromNode(radioTVMetadata, PBCORE_DESCRIPTION_ELEMENT);
        domsClient.updateDataStream(objectPID, PROGRAM_PBCORE_DS_ID, pbCoreDataStreamDocument, comment);
    }

    private void setTitle(Document radioTVMetadata, String filename, String objectPID) throws ServerOperationFailed {
        String programTitle = getTitle(radioTVMetadata);

        log.debug("Found program title '{}', setting this as label on {}", programTitle, objectPID);
        String comment = Util.domsCommenter(filename, "added program title '{0}' object label", programTitle);
        domsClient.setObjectLabel(objectPID, programTitle, comment);
    }

    private String getTitle(Document radioTVMetadata) {
        // Get the program title from the PBCore metadata and use that as the
        // object label for this program object.
        Node titleNode = xPathSelector.selectNode(radioTVMetadata,
                                                  "//pbc:pbcoreTitle[pbc:titleType=\"titel\"]/pbc:title");
        return titleNode.getTextContent();
    }

    private void prepareProgramForOverwrite(String existingPid, String filename, List<String> oldIdentifiers) throws ServerOperationFailed {
        log.debug("Existing object {} will be overwritten", existingPid);
        String comment = Util.domsCommenter(filename, "unpublished object to allow for changes");
        domsClient.unpublishObjects(comment, existingPid);
        log.debug("Existing object {} unpublished", existingPid);
        addOldPids(existingPid, oldIdentifiers, filename);
        log.debug("Old identifiers added to program object {}", existingPid);
    }

    private String createNewProgramObject(String filename, List<String> oldIdentifiers) throws ServerOperationFailed {
        String programObjectPID;// Create a program object in the DOMS and update the PBCore metadata
        // datastream with the PBCore metadata from the pre-ingest file.
        String comment = Util.domsCommenter(filename, "creating Program Object");
        programObjectPID = domsClient.createObjectFromTemplate(PROGRAM_TEMPLATE_PID, oldIdentifiers, comment);
        log.debug("Created new program object with pid {}", programObjectPID);
        return programObjectPID;
    }

    private void addOldPids(String existingPid, List<String> oldIdentifiers, String filename) throws ServerOperationFailed {
        Document dcDataStream = domsClient.getDataStream(existingPid, DC_DS_ID);
        NodeList existingIDNodes = xPathSelector.selectNodeList(dcDataStream, "//dc:identifier");
        Set<String> idsToAdd = new HashSet<String>(oldIdentifiers);
        for (int i = 0; i < existingIDNodes.getLength(); i++) {
            idsToAdd.remove(existingIDNodes.item(i).getTextContent());
        }
        log.debug("Object {} is missing these {} old identifiers", existingPid, idsToAdd);
        if (idsToAdd.isEmpty()) {
            return;
        }

        Node firstIdentifierNode = existingIDNodes.item(0);
        for (String id : idsToAdd) {
            Element newIdentifier = dcDataStream.createElementNS(DC_NAMESPACE, "identifier");
            dcDataStream.setTextContent(id);
            firstIdentifierNode.getParentNode().insertBefore(newIdentifier, firstIdentifierNode);
            log.debug("Adding {} to dc identifiers for object {}", id, existingPid);
        }
        log.debug("Updating {} datastream with new old identifiers {}", DC_DS_ID, oldIdentifiers);
        String comment = Util.domsCommenter(filename, "added old identifiers {0}", oldIdentifiers);
        domsClient.updateDataStream(existingPid, DC_DS_ID, dcDataStream, comment);
    }

    /**
     * Utility method to create a document to ingest from a node.
     *
     * @param radioTVMetadata Bibliographical metadata about the program.
     * @param xpath           XPath expression to select and ingest in node.
     * @return A document containing the metadata form the node selected by xpath.
     */
    private Document createDocumentFromNode(Document radioTVMetadata, String xpath) {
        final Node nodeToIngest = xPathSelector.selectNode(radioTVMetadata, xpath);

        // Build a data document for the data stream in the program object.
        final Document document = documentBuilder.newDocument();
        document.appendChild(document.importNode(nodeToIngest, true));
        return document;
    }

    /**
     * Lookup a program in DOMS.
     * If program exists, returns the PID of the program. Otherwise returns null.
     *
     * @param oldIdentifiers List of old identifiers to look up.
     * @return PID of program, if found. Null otherwise
     * @throws ServerOperationFailed Could not communicate with DOMS.
     */
    private String alreadyExistsInRepo(List<String> oldIdentifiers) throws ServerOperationFailed {
        for (String oldId : oldIdentifiers) {
            try {
                //TODO Remove this when fixed in doms central RI query
                oldId = oldId.replaceAll("'", Matcher.quoteReplacement("\\'"));
                List<String> pids = domsClient.getPidFromOldIdentifier(oldId);
                if (!pids.isEmpty() && !pids.get(0).isEmpty()) {
                    if (pids.size() > 1) {
                        log.warn("Found more than one pids ({}) for old identifiers '{}', returning the first ({})", pids, oldId, pids.get(0));
                    }
                    return pids.get(0);
                }
            } catch (NoObjectFound e) {
                // Ignore, then
            }
        }
        return null;
    }

    /**
     * Find old identifiers in program metadata, and use them for looking up programs in DOMS.
     *
     * @param radioTVMetadata The document containing the program metadata.
     * @return Old indentifiers found.
     */
    private List<String> getOldIdentifiers(Document radioTVMetadata) {
        List<String> result = new ArrayList<String>();

        Node radioTVPBCoreElement = xPathSelector.selectNode(radioTVMetadata, PBCORE_DESCRIPTION_ELEMENT);

        Node oldRitzauPIDNode = xPathSelector.selectNode(radioTVPBCoreElement,
                                                         "pbc:pbcoreIdentifier[pbc:identifierSource=\"id\"]/pbc:identifier");

        if (oldRitzauPIDNode != null && !oldRitzauPIDNode.getTextContent().isEmpty()) {
            result.add(oldRitzauPIDNode.getTextContent());
        }

        Node oldGallupPIDNode = xPathSelector.selectNode(radioTVPBCoreElement,
                                                         "pbc:pbcoreIdentifier[pbc:identifierSource=\"tvmeter\"]/pbc:identifier");

        if (oldGallupPIDNode != null && !oldGallupPIDNode.getTextContent().isEmpty()) {
            result.add(oldGallupPIDNode.getTextContent());
        }
        return result;
    }

    /**
     * Get the PIDs for all the file URLs.
     *
     * @param radioTVMetadata Metadata XML document containing the file information.
     * @return A <code>List</code> of PIDs of the radio-tv file objects found in DOMS.
     * @throws MalformedURLException if a file element contains an invalid URL.
     * @throws ServerOperationFailed if looking up file URL failed.
     * @throws NoObjectFound         if a URL is referenced, which is not found in DOMS.
     */
    private List<String> getFilePids(Document radioTVMetadata) throws MalformedURLException, NoObjectFound, ServerOperationFailed {
        // Get the recording files XML element and process the file information.
        NodeList recordingFileURLs = xPathSelector.selectNodeList(radioTVMetadata, "//program/fileUrls/fileUrl");

        // Find the pids for all referenced file urls.
        List<String> fileObjectPIDs = new ArrayList<String>();
        for (int nodeIndex = 0; nodeIndex < recordingFileURLs.getLength(); nodeIndex++) {
            // Lookup file object.
            Node item = recordingFileURLs.item(nodeIndex);
            String itemTextContent = item.getTextContent();
            log.debug("Found file url {} from metadata", itemTextContent);
            URL fileURL = new URL(itemTextContent);
            String fileObjectPID = domsClient.getFileObjectPID(fileURL);
            fileObjectPIDs.add(fileObjectPID);
            log.debug("Found file object pid {} for file url {}", fileObjectPID, fileURL);
        }
        return fileObjectPIDs;
    }
}

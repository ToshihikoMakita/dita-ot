/*
 * This file is part of the DITA Open Toolkit project.
 *
 * Copyright 2007 IBM Corporation
 *
 * See the accompanying LICENSE file for applicable license.

 */
package org.dita.dost.chunk;

import com.google.common.annotations.VisibleForTesting;
import net.sf.saxon.trans.UncheckedXPathException;
import org.dita.dost.chunk.ChunkOperation.ChunkBuilder;
import org.dita.dost.chunk.ChunkOperation.Operation;
import org.dita.dost.exception.DITAOTException;
import org.dita.dost.log.MessageUtils;
import org.dita.dost.chunk.ChunkModule.ChunkFilenameGenerator;
import org.dita.dost.chunk.ChunkModule.ChunkFilenameGeneratorFactory;
import org.dita.dost.module.reader.TempFileNameScheme;
import org.dita.dost.util.*;
import org.dita.dost.util.Job.FileInfo;
import org.dita.dost.writer.AbstractDomFilter;
import org.w3c.dom.*;
import org.xml.sax.SAXException;

import javax.xml.stream.XMLOutputFactory;
import javax.xml.stream.XMLStreamWriter;
import javax.xml.transform.Result;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.io.*;
import java.net.URI;
import java.util.*;
import java.util.stream.Collectors;

import static java.util.Collections.unmodifiableSet;
import static org.apache.commons.io.FilenameUtils.getBaseName;
import static org.dita.dost.chunk.ChunkOperation.Operation.*;
import static org.dita.dost.reader.GenListModuleReader.isFormatDita;
import static org.dita.dost.util.Constants.*;
import static org.dita.dost.util.FileUtils.getFragment;
import static org.dita.dost.util.FileUtils.replaceExtension;
import static org.dita.dost.util.StringUtils.join;
import static org.dita.dost.util.StringUtils.split;
import static org.dita.dost.util.URLUtils.*;
import static org.dita.dost.util.XMLUtils.*;

/**
 * ChunkMapReader class, read and filter ditamap file for chunking.
 */
final class ChunkMapFilter extends AbstractDomFilter {

    public static final String FILE_NAME_STUB_DITAMAP = "stub.ditamap";
    public static final String FILE_EXTENSION_CHUNK = ".chunk";
    public static final String ATTR_XTRF_VALUE_GENERATED = "generated_by_chunk";

    public static final String CHUNK_SELECT_BRANCH = SELECT_BRANCH.token;
    public static final String CHUNK_SELECT_TOPIC = SELECT_TOPIC.token;
    public static final String CHUNK_SELECT_DOCUMENT = SELECT_DOCUMENT.token;
    private static final String CHUNK_BY_DOCUMENT = BY_DOCUMENT.token;
    private static final String CHUNK_BY_TOPIC = BY_TOPIC.token;
    public static final String CHUNK_TO_CONTENT = TO_CONTENT.token;
    public static final String CHUNK_TO_NAVIGATION = TO_NAVIGATION.token;
    public static final String CHUNK_PREFIX = "Chunk";

    private TempFileNameScheme tempFileNameScheme;
    private Collection<String> rootChunkOverride;
    private String defaultChunkByToken;

    // ChunkTopicParser assumes keys and values are chimera paths, i.e. systems paths with fragments.
    private final LinkedHashMap<URI, URI> changeTable = new LinkedHashMap<>(128);
    @VisibleForTesting
    final List<ChunkOperation> changes = new ArrayList<>();

    private final Map<URI, URI> conflictTable = new HashMap<>(128);

    private boolean supportToNavigation;

    private ProcessingInstruction workdir = null;
    private ProcessingInstruction workdirUrl = null;
    private ProcessingInstruction path2proj = null;
    private ProcessingInstruction path2projUrl = null;
    private ProcessingInstruction path2rootmapUrl = null;

    private final ChunkFilenameGenerator chunkFilenameGenerator = ChunkFilenameGeneratorFactory.newInstance();
    /**
     * Absolute URI to file being processed
     */
    private URI currentFile;


    @Override
    public void setJob(final Job job) {
        super.setJob(job);
        try {
            tempFileNameScheme = (TempFileNameScheme) Class.forName(job.getProperty("temp-file-name-scheme")).newInstance();
        } catch (InstantiationException | IllegalAccessException | ClassNotFoundException e) {
            throw new RuntimeException(e);
        }
        tempFileNameScheme.setBaseDir(job.getInputDir());
    }

    public void setRootChunkOverride(final String chunkValue) {
        rootChunkOverride = split(chunkValue);
    }

    /**
     * read input file.
     *
     * @param inputFile filename
     */
    @Override
    public void read(final File inputFile) throws DITAOTException {
        this.currentFile = inputFile.toURI();

        super.read(inputFile);
    }

    @Override
    public Document process(final Document doc) {
        readLinks(doc);
        readProcessingInstructions(doc);

        final Element root = doc.getDocumentElement();
        if (rootChunkOverride != null) {
            final String c = join(rootChunkOverride, " ");
            logger.debug("Use override root chunk \"" + c + "\"");
            root.setAttribute(ATTRIBUTE_NAME_CHUNK, c);
        }
        final Collection<String> rootChunk = split(root.getAttribute(ATTRIBUTE_NAME_CHUNK));
        defaultChunkByToken = getChunkByToken(rootChunk, "by-", CHUNK_BY_DOCUMENT);

        if (rootChunk.contains(CHUNK_TO_CONTENT)) {
            chunkMap(root);
        } else {
            for (final Element child : getChildElements(root)) {
                if (MAP_RELTABLE.matches(child)) {
                    updateReltable(child);
                } else if (MAP_TOPICREF.matches(child)) {
                    processTopicref(child);
                }
            }
        }

        return buildOutputDocument(root);
    }

    private final Set<URI> chunkTopicSet = new HashSet<>();

    /**
     * @return absolute temporary files
     */
    public Set<URI> getChunkTopicSet() {
        return unmodifiableSet(chunkTopicSet);
    }

    public List<ChunkOperation> getChunks() {
        return changes;
    }

    private void readLinks(final Document doc) {
        final Element root = doc.getDocumentElement();
        readLinks(root, false, false);
    }

    private void readLinks(final Element elem, final boolean chunk, final boolean disabled) {
        final boolean c = chunk || elem.getAttributeNode(ATTRIBUTE_NAME_CHUNK) != null;
        final boolean d = disabled
                || elem.getAttribute(ATTRIBUTE_NAME_CHUNK).contains(CHUNK_TO_NAVIGATION)
                || (MAPGROUP_D_TOPICGROUP.matches(elem) && !SUBMAP.matches(elem))
                || MAP_RELTABLE.matches(elem);
        final Attr href = elem.getAttributeNode(ATTRIBUTE_NAME_HREF);
        if (href != null) {
            final URI filename = stripFragment(currentFile.resolve(href.getValue()));
            if (c && !d) {
                chunkTopicSet.add(filename);
                final Attr copyTo = elem.getAttributeNode(ATTRIBUTE_NAME_COPY_TO);
                if (copyTo != null) {
                    final URI copyToFile = stripFragment(currentFile.resolve(copyTo.getValue()));
                    chunkTopicSet.add(copyToFile);
                }
            }
        }

        for (final Element topicref : getChildElements(elem, MAP_TOPICREF)) {
            readLinks(topicref, c, d);
        }
    }

    public static String getChunkByToken(final Collection<String> chunkValue, final String category, final String defaultToken) {
        if (chunkValue.isEmpty()) {
            return defaultToken;
        }
        for (final String token : chunkValue) {
            if (token.startsWith(category)) {
                return token;
            }
        }
        return defaultToken;
    }

    /**
     * Process map when "to-content" is specified on map element.
     * <p>
     * TODO: Instead of reclassing map element to be a topicref, add a topicref
     * into the map root and move all map content into that topicref.
     */
    private void chunkMap(final Element root) {
        // create the reference to the new file on root element.
        String newFilename = replaceExtension(new File(currentFile).getName(), FILE_EXTENSION_DITA);
        URI newFile = currentFile.resolve(newFilename);
        if (new File(newFile).exists()) {
            final URI oldFile = newFile;
            newFilename = chunkFilenameGenerator.generateFilename(CHUNK_PREFIX, FILE_EXTENSION_DITA);
            newFile = currentFile.resolve(newFilename);
            // Mark up the possible name changing, in case that references might be updated.
            conflictTable.put(newFile, oldFile.normalize());
        }
        changeTable.put(newFile, newFile);
//        changes.add(ChunkOperation.builder(BY_DOCUMENT).src(newFile).dst(newFile).build());

        // change the class attribute to "topicref"
        final String origCls = root.getAttribute(ATTRIBUTE_NAME_CLASS);
        root.setAttribute(ATTRIBUTE_NAME_CLASS, origCls + MAP_TOPICREF.matcher);
        root.setAttribute(ATTRIBUTE_NAME_HREF, toURI(newFilename).toString());

        createTopicStump(newFile);

        // process chunk
        processTopicref(root);

        // restore original root element
        if (origCls != null) {
            root.setAttribute(ATTRIBUTE_NAME_CLASS, origCls);
        }
        root.removeAttribute(ATTRIBUTE_NAME_HREF);
    }

    /**
     * Create the new topic stump.
     */
    private void createTopicStump(final URI newFile) {
        try (final OutputStream newFileWriter = new FileOutputStream(new File(newFile))) {
            final XMLStreamWriter o = XMLOutputFactory.newInstance().createXMLStreamWriter(newFileWriter, UTF8);
            o.writeStartDocument();
            o.writeProcessingInstruction(PI_WORKDIR_TARGET, UNIX_SEPARATOR + new File(newFile.resolve(".")).getAbsolutePath());
            o.writeProcessingInstruction(PI_WORKDIR_TARGET_URI, newFile.resolve(".").toString());
            o.writeStartElement(ELEMENT_NAME_DITA);
            o.writeEndElement();
            o.writeEndDocument();
            o.close();
            newFileWriter.flush();
        } catch (final RuntimeException e) {
            throw e;
        } catch (final Exception e) {
            logger.error(e.getMessage(), e);
        }
    }

    /**
     * Read processing metadata from processing instructions.
     */
    private void readProcessingInstructions(final Document doc) {
        final NodeList docNodes = doc.getChildNodes();
        for (int i = 0; i < docNodes.getLength(); i++) {
            final Node node = docNodes.item(i);
            if (node.getNodeType() == Node.PROCESSING_INSTRUCTION_NODE) {
                final ProcessingInstruction pi = (ProcessingInstruction) node;
                switch (pi.getNodeName()) {
                    case PI_WORKDIR_TARGET:
                        workdir = pi;
                        break;
                    case PI_WORKDIR_TARGET_URI:
                        workdirUrl = pi;
                        break;
                    case PI_PATH2PROJ_TARGET:
                        path2proj = pi;
                        break;
                    case PI_PATH2PROJ_TARGET_URI:
                        path2projUrl = pi;
                        break;
                    case PI_PATH2ROOTMAP_TARGET_URI:
                        path2rootmapUrl = pi;
                        break;
                }
            }
        }
    }

    private void outputMapFile(final URI file, final Document doc) {
        Result result = null;
        try {
            final Transformer serializer = TransformerFactory.newInstance().newTransformer();
            result = new StreamResult(new FileOutputStream(new File(file)));
            serializer.transform(new DOMSource(doc), result);
        } catch (final UncheckedXPathException e) {
            logger.error(e.getXPathException().getMessageAndLocation(), e);
        } catch (final RuntimeException e) {
            throw e;
        } catch (final TransformerException e) {
            logger.error(e.getMessageAndLocation(), e);
        } catch (final Exception e) {
            logger.error(e.getMessage(), e);
        } finally {
            try {
                close(result);
            } catch (final IOException e) {
                logger.error(e.getMessage(), e);
            }
        }
    }

    private Document buildOutputDocument(final Element root) {
        final Document doc = getDocumentBuilder().newDocument();
        if (workdir != null) {
            doc.appendChild(doc.importNode(workdir, true));
        }
        if (workdirUrl != null) {
            doc.appendChild(doc.importNode(workdirUrl, true));
        }
        if (path2proj != null) {
            doc.appendChild(doc.importNode(path2proj, true));
        }
        if (path2projUrl != null) {
            doc.appendChild(doc.importNode(path2projUrl, true));
        }
        if (path2rootmapUrl != null) {
            doc.appendChild(doc.importNode(path2rootmapUrl, true));
        }
        doc.appendChild(doc.importNode(root, true));
        return doc;
    }

    private void processTopicref(final Element topicref) {
        final String xtrf = getValue(topicref, ATTRIBUTE_NAME_XTRF);
        if (xtrf != null && xtrf.contains(ATTR_XTRF_VALUE_GENERATED)) {
            return;
        }

        final Collection<String> chunk = split(getValue(topicref, ATTRIBUTE_NAME_CHUNK));
        final Operation select = getSelect(chunk);

        URI href = toURI(getValue(topicref, ATTRIBUTE_NAME_HREF));
        final URI copyTo = toURI(getValue(topicref, ATTRIBUTE_NAME_COPY_TO));
        final String scope = getCascadeValue(topicref, ATTRIBUTE_NAME_SCOPE);
        final String chunkByToken = getChunkByToken(chunk, "by-", defaultChunkByToken);

        if (ATTR_SCOPE_VALUE_EXTERNAL.equals(scope)
                || (href != null && !toFile(currentFile.resolve(href.toString())).exists())
                || (chunk.isEmpty() && href == null)) {
            processChildTopicref(topicref);
        } else if (chunk.contains(CHUNK_TO_CONTENT)) {
//            if (href != null || copyTo != null || topicref.hasChildNodes()) {
            if (chunk.contains(CHUNK_BY_TOPIC)) {
                logger.warn(MessageUtils.getMessage("DOTJ064W").setLocation(topicref).toString());
            }

            final ChunkBuilder op = ChunkOperation.builder(TO_CONTENT)
                    .select(select)
                    .src(href != null ? currentFile.resolve(href) : null);

            final URI dst;
            if (copyTo != null) {
                dst = copyTo;
            } else if (href != null) {
                dst = href;
            } else {
                // FIXME we should not generate stump yet
                dst = generateStumpTopic(topicref);
            }
            op.dst(currentFile.resolve(dst));

//            processCombineChunk(topicref, op);
            collectCombineChunk(getChildElements(topicref, MAP_TOPICREF), op);
            changes.add(op.build());
//            }
            processChildTopicref(topicref);
        } else if (chunk.contains(CHUNK_TO_NAVIGATION)
                && supportToNavigation) {
            processChildTopicref(topicref);
            processNavitation(topicref);
        } else if (chunkByToken.equals(CHUNK_BY_TOPIC)) {
            if (href != null) {
                readByTopic(href, select);
                processSeparateChunk(topicref);
            } else {
                logger.warn("{} set on topicref without href ", CHUNK_BY_TOPIC);
            }
            processChildTopicref(topicref);
        } else { // chunkByToken.equals(CHUNK_BY_DOCUMENT)
            URI currentPath = null;
            if (copyTo != null) {
                currentPath = currentFile.resolve(copyTo);
            } else if (href != null) {
                currentPath = currentFile.resolve(href);
            }
            if (currentPath != null) {
                changeTable.remove(currentPath);
                final String processingRole = getCascadeValue(topicref, ATTRIBUTE_NAME_PROCESSING_ROLE);
                if (!ATTR_PROCESSING_ROLE_VALUE_RESOURCE_ONLY.equals(processingRole)) {
                    changeTable.put(currentPath, currentPath);
//                    changes.add(ChunkOperation.builder(BY_DOCUMENT).src(currentPath).dst(currentPath).build());
                }
            }
            processChildTopicref(topicref);
        }
    }

    /**
     * Read topic to find by-topic chunks.
     */
    private void readByTopic(final URI href, final Operation select) {
        try {
            final URI file = URLUtils.stripFragment(currentFile.resolve(href));
            final Document document = getDocumentBuilder().parse(file.toString());
            final ChunkBuilder op = walkByTopic(document.getDocumentElement(), href);
            changes.add(op.build());
        } catch (SAXException | IOException e) {
            logger.error("Failed to read {}: {}", href, e.getMessage(), e);
        }
    }

    private ChunkBuilder walkByTopic(final Element topic, final URI src) {
        final String id = topic.getAttribute(ATTRIBUTE_NAME_ID);
        final ChunkBuilder op = ChunkOperation.builder(BY_TOPIC)
                .src(setFragment(src, id));
        final List<Element> childTopics = getChildElements(topic, TOPIC_TOPIC);
        for (Element childTopic : childTopics) {
            final ChunkBuilder chunkBuilders = walkByTopic(childTopic, src);
            op.addChild(chunkBuilders);
        }
        return op;
    }

    /**
     * Create new map and refer to it with navref.
     */
    private void processNavitation(final Element topicref) {
        // create new map's root element
        final Element root = (Element) topicref.getOwnerDocument().getDocumentElement().cloneNode(false);
        // create navref element
        final Element navref = topicref.getOwnerDocument().createElement(MAP_NAVREF.localName);
        final String newMapFile = chunkFilenameGenerator.generateFilename("MAPCHUNK", FILE_EXTENSION_DITAMAP);
        navref.setAttribute(ATTRIBUTE_NAME_MAPREF, newMapFile);
        navref.setAttribute(ATTRIBUTE_NAME_CLASS, MAP_NAVREF.toString());
        // replace topicref with navref
        topicref.getParentNode().replaceChild(navref, topicref);
        root.appendChild(topicref);
        // generate new file
        final URI navmap = currentFile.resolve(newMapFile);
        changeTable.put(stripFragment(navmap), stripFragment(navmap));
        outputMapFile(navmap, buildOutputDocument(root));
    }

    /**
     * Generate file name.
     *
     * @return generated file name
     */
    private String generateFilename() {
        return chunkFilenameGenerator.generateFilename(CHUNK_PREFIX, FILE_EXTENSION_DITA);
    }

    /**
     * Generate stump topic for to-content content.
     *
     * @param topicref topicref without href to generate stump topic for
     */
    private URI generateStumpTopic(final Element topicref) {
        final URI result = getResultFile(topicref);
        final URI temp = tempFileNameScheme.generateTempFileName(result);
        final URI absTemp = job.tempDir.toURI().resolve(temp);

        final String name = getBaseName(new File(result).getName());
        String navtitle = getChildElementValueOfTopicmeta(topicref, TOPIC_NAVTITLE);
        if (navtitle == null) {
            navtitle = getValue(topicref, ATTRIBUTE_NAME_NAVTITLE);
        }
        final String shortDesc = getChildElementValueOfTopicmeta(topicref, MAP_SHORTDESC);

        writeChunk(absTemp, name, navtitle, shortDesc);

        // update current element's @href value
        final URI relativePath = getRelativePath(currentFile.resolve(FILE_NAME_STUB_DITAMAP), absTemp);
        topicref.setAttribute(ATTRIBUTE_NAME_HREF, relativePath.toString());
        if (MAPGROUP_D_TOPICGROUP.matches(topicref)) {
            topicref.setAttribute(ATTRIBUTE_NAME_CLASS, MAP_TOPICREF.toString());
        }

//        final URI relativeToBase = getRelativePath(job.tempDirURI.resolve("dummy"), absTemp);
        final FileInfo fi = new FileInfo.Builder()
                .uri(temp)
                .result(result)
                .format(ATTR_FORMAT_VALUE_DITA)
                .build();
        job.add(fi);

        return relativePath;
    }

    private void writeChunk(final URI outputFileName, String id, String title, String shortDesc) {
        try (final OutputStream output = new FileOutputStream(new File(outputFileName))) {
            final XMLSerializer serializer = XMLSerializer.newInstance(output);
            serializer.writeStartDocument();
            if (title == null && shortDesc == null) {
                //topicgroup with no title, no shortdesc, just need a non titled stub
                serializer.writeStartElement(ELEMENT_NAME_DITA);
                serializer.writeAttribute(DITA_NAMESPACE, ATTRIBUTE_PREFIX_DITAARCHVERSION + ":" + ATTRIBUTE_NAME_DITAARCHVERSION, "1.3");
                serializer.writeEndElement(); // dita
            } else {
                serializer.writeStartElement(TOPIC_TOPIC.localName);
                serializer.writeAttribute(DITA_NAMESPACE, ATTRIBUTE_PREFIX_DITAARCHVERSION + ":" + ATTRIBUTE_NAME_DITAARCHVERSION, "1.3");
                serializer.writeAttribute(ATTRIBUTE_NAME_ID, id);
                serializer.writeAttribute(ATTRIBUTE_NAME_CLASS, TOPIC_TOPIC.toString());
                serializer.writeAttribute(ATTRIBUTE_NAME_DOMAINS, "");
                serializer.writeStartElement(TOPIC_TITLE.localName);
                serializer.writeAttribute(ATTRIBUTE_NAME_CLASS, TOPIC_TITLE.toString());
                if (title != null) {
                    serializer.writeCharacters(title);
                }
                serializer.writeEndElement(); // title
                if (shortDesc != null) {
                    serializer.writeStartElement(TOPIC_SHORTDESC.localName);
                    serializer.writeAttribute(ATTRIBUTE_NAME_CLASS, TOPIC_SHORTDESC.toString());
                    serializer.writeCharacters(shortDesc);
                    serializer.writeEndElement(); // shortdesc
                }
                serializer.writeEndElement(); // topic
            }
            serializer.writeEndDocument();
            serializer.close();
        } catch (final IOException | SAXException e) {
            logger.error("Failed to write generated chunk: " + e.getMessage(), e);
        }
    }

    private URI getResultFile(final Element topicref) {
        final FileInfo curr = job.getFileInfo(currentFile);
        final URI copyTo = toURI(getValue(topicref, ATTRIBUTE_NAME_COPY_TO));
        final String id = getValue(topicref, ATTRIBUTE_NAME_ID);

        URI outputFileName;
        if (copyTo != null) {
            outputFileName = curr.result.resolve(copyTo);
        } else if (id != null) {
            outputFileName = curr.result.resolve(id + FILE_EXTENSION_DITA);
        } else {
            final Set<URI> results = job.getFileInfo().stream().map(fi -> fi.result).collect(Collectors.toSet());
            do {
                outputFileName = curr.result.resolve(generateFilename());
            } while (results.contains(outputFileName));
        }
        return outputFileName;
    }

    /**
     * get topicmeta's child(e.g navtitle, shortdesc) tag's value(text-only).
     *
     * @param element input element
     * @return text value
     */
    private String getChildElementValueOfTopicmeta(final Element element, final DitaClass classValue) {
        if (element.hasChildNodes()) {
            final Element topicMeta = getElementNode(element, MAP_TOPICMETA);
            if (topicMeta != null) {
                final Element elem = getElementNode(topicMeta, classValue);
                if (elem != null) {
                    return getText(elem);
                }
            }
        }
        return null;
    }

    private void processChildTopicref(final Element node) {
//        final List<Element> children = getChildElements(node, MAP_TOPICREF);
//        for (final Element currentElem : children) {
////            final URI href = toURI(getValue(currentElem, ATTRIBUTE_NAME_HREF));
////            final String xtrf = currentElem.getAttribute(ATTRIBUTE_NAME_XTRF);
////            if (href == null) {
////                processTopicref(currentElem);
////            } else if (!ATTR_XTRF_VALUE_GENERATED.equals(xtrf)
////                    && !currentFile.resolve(href).equals(changeTable.get(currentFile.resolve(href)))) {
//            processTopicref(currentElem);
////            }
//        }
        getChildElements(node, MAP_TOPICREF).forEach(this::processTopicref);
    }

    private void processSeparateChunk(final Element topicref) {
        final SeparateChunkTopicParser chunkParser = new SeparateChunkTopicParser();
        chunkParser.setLogger(logger);
        chunkParser.setJob(job);
        chunkParser.setup(changeTable, conflictTable, topicref, chunkFilenameGenerator);
        chunkParser.write(currentFile);
    }

//    private void processCombineChunk(final Element topicref, final ChunkBuilder chunkToContent) {
//        collectCombineChunk(getChildElements(topicref, MAP_TOPICREF), chunkToContent);
////        createChildTopicrefStubs(getChildElements(topicref, MAP_TOPICREF), chunkToContent);
//
////        final ChunkTopicParser chunkParser = new ChunkTopicParser();
////        chunkParser.setLogger(logger);
////        chunkParser.setJob(job);
////        chunkParser.setup(changeTable, conflictTable, topicref, chunkFilenameGenerator);
////        chunkParser.write(currentFile);
////        processChunk(topicref, null);
//    }

    private void processChunk(final Element topicref, final URI outputFile) {
        final URI hrefValue = toURI(getValue(topicref, ATTRIBUTE_NAME_HREF));
        final Collection<String> chunkValue = split(getValue(topicref, ATTRIBUTE_NAME_CHUNK));
        final URI copytoValue = toURI(getValue(topicref, ATTRIBUTE_NAME_COPY_TO));
        final String scopeValue = getCascadeValue(topicref, ATTRIBUTE_NAME_SCOPE);
        final String classValue = getValue(topicref, ATTRIBUTE_NAME_CLASS);
        final String processRoleValue = getCascadeValue(topicref, ATTRIBUTE_NAME_PROCESSING_ROLE);
        final String formatValue = getValue(topicref, ATTRIBUTE_NAME_FORMAT);

        URI outputFileName = outputFile;
        Set<String> tempTopicID = null;

        final URI parseFilePath;
        if (copytoValue != null) { // && !chunkValue.contains(CHUNK_TO_CONTENT)
            if (hrefValue.getFragment() != null) {
                parseFilePath = setFragment(copytoValue, hrefValue.getFragment());
            } else {
                parseFilePath = copytoValue;
            }
        } else {
            parseFilePath = hrefValue;
        }

//        if (parseFilePath != null && !ATTR_SCOPE_VALUE_EXTERNAL.equals(scopeValue) && isFormatDita(formatValue)) {
//            // now the path to target file make sense
//            if (chunkValue.contains(CHUNK_TO_CONTENT)) {
//                // if current element contains "to-content" in chunk attribute
//                // we need to create new buffer and flush the buffer to file
//                // after processing is finished
////                    tempWriter = output;
//                tempTopicID = topicID;
////                    output = new StringWriter();
//                topicID = new HashSet<>();
//                if (MAP_MAP.matches(classValue)) {
//                    // Very special case, we have a map element with href value.
//                    // This is a map that needs to be chunked to content.
//                    // No need to parse any file, just generate a stub output.
//                    outputFileName = currentFile.resolve(parseFilePath);
//                } else if (copytoValue != null) {
//                    // use @copy-to value as the new file name
//                    outputFileName = currentFile.resolve(copytoValue);
//                } else if (hrefValue != null) {
//                    // try to use href value as the new file name
//                    if (chunkValue.contains(CHUNK_SELECT_TOPIC)
//                            || chunkValue.contains(CHUNK_SELECT_BRANCH)) {
//                        if (hrefValue.getFragment() != null) {
//                            // if we have an ID here, use it.
//                            outputFileName = currentFile.resolve(hrefValue.getFragment() + FILE_EXTENSION_DITA);
//                        } else {
//                            // Find the first topic id in target file if any.
//                            final String firstTopic = getFirstTopicId(new File(stripFragment(currentFile.resolve(hrefValue))));
//                            if (firstTopic != null) {
//                                outputFileName = currentFile.resolve(firstTopic + FILE_EXTENSION_DITA);
//                            } else {
//                                outputFileName = currentFile.resolve(hrefValue);
//                            }
//                        }
//                    } else {
//                        // otherwise, use the href value instead
//                        outputFileName = currentFile.resolve(hrefValue);
//                    }
//                } else {
//                    // use randomly generated file name
//                    outputFileName = generateOutputFile(currentFile);
//                }
//
//                // Check if there is any conflict
//                if (new File(URLUtils.stripFragment(outputFileName)).exists() && !MAP_MAP.matches(classValue)) {
//                    final URI t = outputFileName;
//                    outputFileName = generateOutputFile(currentFile);
//                    conflictTable.put(outputFileName, t);
//                }
//                // add newly generated file to changTable
//                // the new entry in changeTable has same key and value
//                // in order to indicate it is a newly generated file
//                changeTable.put(outputFileName, outputFileName);
//
//                final FileInfo fi = generateFileInfo(outputFileName);
//                job.add(fi);
//            }
//            // "by-topic" couldn't reach here
//            this.outputFile = outputFileName;
//
//            final URI path = currentFile.resolve(parseFilePath);
//            URI newpath;
//            if (path.getFragment() != null) {
//                newpath = setFragment(outputFileName, path.getFragment());
//            } else {
//                final String firstTopicID = getFirstTopicId(new File(path));
//                if (firstTopicID != null) {
//                    newpath = setFragment(outputFileName, firstTopicID);
//                } else {
//                    newpath = outputFileName;
//                }
//            }
//            // add file name changes to changeTable, this will be used in
//            // TopicRefWriter's updateHref method, very important!!!
//            changeTable.put(path, newpath);
//            // update current element's @href value
//            topicref.setAttribute(ATTRIBUTE_NAME_HREF, getRelativePath(currentFile.resolve(FILE_NAME_STUB_DITAMAP), newpath).toString());
//
//            if (parseFilePath.getFragment() != null) {
//                targetTopicId = parseFilePath.getFragment();
//            }
//
//            final String s = getChunkByToken(chunkValue, "select-", null);
//            if (s != null) {
//                selectMethod = s;
//                // if the current topic href referred to a entire
//                // topic file, it will be handled in "document" level.
//                if (targetTopicId == null) {
//                    selectMethod = CHUNK_SELECT_DOCUMENT;
//                }
//            }
//            final URI tempPath = currentParsingFile;
//            currentParsingFile = currentFile.resolve(parseFilePath);
//
//            if (!ATTR_PROCESSING_ROLE_VALUE_RESOURCE_ONLY.equals(processRoleValue)) {
//                currentParsingFileTopicIDChangeTable = new HashMap<>();
//                // TODO recursive point
//                logger.info("Processing " + currentParsingFile);
//                reader.parse(currentParsingFile.toString());
//                if (currentParsingFileTopicIDChangeTable.size() > 0) {
//                    final URI href = toURI(topicref.getAttribute(ATTRIBUTE_NAME_HREF));
//                    final String pathtoElem = href.getFragment() != null
//                            ? href.getFragment()
//                            : "";
//                    final String old_elementid = pathtoElem.contains(SLASH)
//                            ? pathtoElem.substring(0, pathtoElem.indexOf(SLASH))
//                            : pathtoElem;
//                    if (!old_elementid.isEmpty()) {
//                        final String new_elementid = currentParsingFileTopicIDChangeTable.get(old_elementid);
//                        if (new_elementid != null && !new_elementid.isEmpty()) {
//                            topicref.setAttribute(ATTRIBUTE_NAME_HREF, setFragment(href, new_elementid).toString());
//                        }
//                    }
//                }
//                currentParsingFileTopicIDChangeTable = null;
//            }
//            // restore the currentParsingFile
//            currentParsingFile = tempPath;
//        }

        if (topicref.hasChildNodes()) {
            final List<Element> children = getChildElements(topicref, MAP_TOPICREF);
            for (final Element current : children) {
                processChunk(current, outputFileName);
            }
        }
    }
    
//    /** Before combining topics in a branch, ensure any descendant topicref with @chunk and no @href has a stub */
//    private void createChildTopicrefStubs(final List<Element> topicrefs, final ChunkBuilder parent) {
//        if (!topicrefs.isEmpty()) {
//            for (final Element currentElem : topicrefs) {
//                final Collection<String> chunks = split(getValue(currentElem, ATTRIBUTE_NAME_CHUNK));
//                if (parent != null && !chunks.contains(CHUNK_TO_CONTENT)) {
//                    URI href = toURI(getValue(currentElem, ATTRIBUTE_NAME_HREF));
//                    if (href == null) {
//                        href = generateStumpTopic(currentElem);
//                    }
//                    final ChunkBuilder ops = ChunkOperation.builder(null)
//                            .src(currentFile.resolve(href));
//                    parent.addChild(ops);
//                    createChildTopicrefStubs(getChildElements(currentElem, MAP_TOPICREF), ops);
//                }
//            }
//        }
//    }

    private Operation getSelect(final Collection<String> chunks) {
        for (String chunk : chunks) {
            switch (chunk) {
                case "select-topic":
                    return SELECT_TOPIC;
                case "select-document":
                    return SELECT_DOCUMENT;
                case "select-branch":
                    return SELECT_BRANCH;
            }
        }
        return null;
    }

    private void collectCombineChunk(final List<Element> topicrefs, final ChunkBuilder parent) {
        if (!topicrefs.isEmpty()) {
            for (final Element currentElem : topicrefs) {
                final Collection<String> chunks = split(getValue(currentElem, ATTRIBUTE_NAME_CHUNK));
                if (parent != null && !chunks.contains(CHUNK_TO_CONTENT)) {
                    final Operation select = getSelect(chunks);
                    URI href = toURI(getValue(currentElem, ATTRIBUTE_NAME_HREF));
//                    if (href == null) {
//                        href = generateStumpTopic(currentElem);
//                    }
                    final ChunkBuilder ops = ChunkOperation.builder(null)
                            .select(select)
                            .src(href != null ? currentFile.resolve(href) : null);
                    parent.addChild(ops);
                    collectCombineChunk(getChildElements(currentElem, MAP_TOPICREF), ops);
                }
            }
        }
    }

    private void updateReltable(final Element elem) {
        final String href = elem.getAttribute(ATTRIBUTE_NAME_HREF);
        if (href.length() != 0) {
            if (changeTable.containsKey(currentFile.resolve(href))) {
                URI res = getRelativePath(currentFile.resolve(FILE_NAME_STUB_DITAMAP),
                        currentFile.resolve(href));
                final String fragment = getFragment(href);
                if (fragment != null) {
                    res = setFragment(res, fragment);
                }
                elem.setAttribute(ATTRIBUTE_NAME_HREF, res.toString());
            }
        }
        final NodeList children = elem.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            final Node current = children.item(i);
            if (current.getNodeType() == Node.ELEMENT_NODE) {
                final Element currentElem = (Element) current;
                final String cls = currentElem.getAttribute(ATTRIBUTE_NAME_CLASS);
                if (MAP_TOPICREF.matches(cls)) {
                    // FIXME: What should happen here?
                }
            }
        }
    }

    /**
     * Get changed files table.
     *
     * @return map of changed files, absolute temporary files
     */
    public Map<URI, URI> getChangeTable() {
        for (final Map.Entry<URI, URI> e : changeTable.entrySet()) {
            assert e.getKey().isAbsolute();
            assert e.getValue().isAbsolute();
        }
        return Collections.unmodifiableMap(changeTable);
    }

    /**
     * get conflict table.
     *
     * @return conflict table, absolute temporary files
     */
    public Map<URI, URI> getConflicTable() {
        for (final Map.Entry<URI, URI> e : conflictTable.entrySet()) {
            assert e.getKey().isAbsolute();
            assert e.getValue().isAbsolute();
        }
        return conflictTable;
    }

    /**
     * Support chunk token to-navigation.
     *
     * @param supportToNavigation flag to enable to-navigation support
     */
    public void supportToNavigation(final boolean supportToNavigation) {
        this.supportToNavigation = supportToNavigation;
    }

}

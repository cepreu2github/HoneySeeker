package org.honeyseeker;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import net.greypanther.natsort.CaseInsensitiveSimpleNaturalComparator;
import org.apache.commons.io.ByteOrderMark;
import org.apache.commons.io.input.BOMInputStream;
import org.w3c.dom.Document;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.xpath.*;
import java.io.*;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

@RequiredArgsConstructor
public class Searcher {
    private static final int CONTEXT_SIZE = 300;
    @Getter
    private SearchResult lastResult = new SearchResult();
    private final Logger logger;
    public volatile boolean shouldStop = false;

    public SearchResult doSearch(Config config, boolean isBackwards) throws SearcherException {
        if (lastResult.getEncounters().isEmpty()) {
            logger.logInfo("start search");
            lastResult = processZipFiles(config, false, isBackwards);
        } else {
            logger.logInfo("continue search");
            lastResult = processZipFiles(config, true, isBackwards);
        }

        return lastResult;
    }

    private SearchResult processZipFiles(Config config, boolean shouldSkipCurrent, boolean isBackwards)
            throws SearcherException {
        File folder = new File(config.getFolder());

        if (!folder.exists() || !folder.isDirectory()) {
            logger.logWarn("folder not exists or not a folder");
            return new SearchResult();
        }

        File[] zipFiles = folder.listFiles((dir, name) -> name.toLowerCase().endsWith(".zip"));
        if (zipFiles == null || zipFiles.length == 0) {
            return new SearchResult();
        }

        Comparator<File> comparator = Comparator.comparing(
                File::getName,
                CaseInsensitiveSimpleNaturalComparator.getInstance()
        );
        if (isBackwards) {
            comparator = comparator.reversed();
        }
        Arrays.sort(zipFiles, comparator);

        boolean foundStartPoint = config.getCurrentFile().isEmpty();
        for (File zipFile : zipFiles) {
            if (!foundStartPoint && zipFile.getName().equals(config.getCurrentFile())) {
                foundStartPoint = true;
            }
            if (!foundStartPoint) {
                continue;
            }
            logger.logInfo("process archive: " + zipFile.getName());
            SearchResult result = processSingleZipFile(zipFile, config, shouldSkipCurrent, isBackwards);
            if (!result.getEncounters().isEmpty()) {
                return result;
            }
            config.setCurrentEntry("");
        }
        logger.logInfo("end of search");
        return new SearchResult();
    }

    private SearchResult processSingleZipFile(File zipFile, Config config, boolean shouldSkipCurrent,
                                              boolean isBackwards)
            throws SearcherException {
        try (ZipFile zip = new ZipFile(zipFile)) {
            List<String> fileNames = new ArrayList<>();
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (!entry.isDirectory()) {
                    fileNames.add(entry.getName());
                }
            }

            Comparator<String> comparator = CaseInsensitiveSimpleNaturalComparator.getInstance();
            if (isBackwards) {
                comparator = comparator.reversed();
            }
            fileNames.sort(comparator);
            boolean foundStartPoint = config.getCurrentEntry().isEmpty();
            for (String fileName : fileNames) {
                ZipEntry entry = zip.getEntry(fileName);
                if (entry != null && entry.getName().endsWith(".fb2")) {
                    if (!foundStartPoint && entry.getName().equals(config.getCurrentEntry())) {
                        foundStartPoint = true;
                        if (shouldSkipCurrent) {
                            continue;
                        }
                    }
                    if (!foundStartPoint) {
                        continue;
                    }
                    logger.logInfo("read file: " + entry.getName());
                    SearchResult result = searchInZipEntryContent(zip, entry, config);
                    if (!result.getEncounters().isEmpty()) {
                        return result;
                    }
                }
            }
        } catch (IOException e) {
            String errorMessage = "opening zip " + zipFile.getName() + ", details: " + e;
            throw new SearcherException(errorMessage, zipFile.getName(), "", e);
        } catch (SearcherException e) {
            e.setCurrentFile(zipFile.getName());
            throw e;
        }
        return new SearchResult();
    }

    private SearchResult searchInZipEntryContent(ZipFile zip, ZipEntry entry, Config config) throws SearcherException {
        String bookFullXml;
        String bookTextOnly;

        if (shouldStop) {
            shouldStop = false;
            throw new InterruptedByUserSearcherException(
                    "forced stop", null, entry.getName(), new InterruptedException("stop")
            );
        }

        try (BOMInputStream bomInputStream = BOMInputStream.builder()
                .setByteOrderMarks(
                        ByteOrderMark.UTF_8, ByteOrderMark.UTF_16LE, ByteOrderMark.UTF_16BE, ByteOrderMark.UTF_32LE,
                        ByteOrderMark.UTF_32BE
                )
                .setInputStream(zip.getInputStream(entry)).get();
             InputStreamReader inputStreamReader = new InputStreamReader(
                     bomInputStream, XmlCharsetDetector.getCharsetFromXml(zip, entry, logger)
             );
             BufferedReader reader = new BufferedReader(inputStreamReader)) {
            bookFullXml = reader.lines().collect(Collectors.joining(" "));
        } catch (IOException e) {
            String errorMessage = "reading fb2 " + entry.getName() + ", details: " + e;
            throw new SearcherException(errorMessage, null, entry.getName(), e);
        }

        try (StringReader stringReader = new StringReader(bookFullXml);
             BufferedReader reader = new BufferedReader(stringReader)) {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            DocumentBuilder builder = factory.newDocumentBuilder();
            Document document = builder.parse(new InputSource(reader));
            bookTextOnly = getBookRawText(document);
        } catch (ParserConfigurationException | SAXException | XPathExpressionException | IOException e) {
            String warningMessage = "warning, failed to parse fb2 " + entry.getName() + ", details: " + e;
            logger.logWarn(warningMessage);
            bookTextOnly = ""; // will continue to use full XML as fallback
        }
        return searchInText(bookTextOnly, bookFullXml, config.getSearchQuery(), zip, entry);
    }

    private SearchResult searchInText(String bookTextOnly, String bookFullXml, String searchQuery,
                                      ZipFile zip, ZipEntry entry) {
        List<SearchResult.SearchEncounter> rawResults = searchInText(bookTextOnly, searchQuery, CONTEXT_SIZE);
        List<SearchResult.SearchEncounter> xmlResults = searchInText(bookFullXml, searchQuery, CONTEXT_SIZE);
        List<SearchResult.SearchEncounter> results = rawResults.size() > xmlResults.size() ? rawResults: xmlResults;

        SearchResult result = new SearchResult();
        if (results.isEmpty()) {
            return result;
        }

        List<SearchResult.SearchEncounter> genres = searchInText(bookFullXml, "(?<=<genre>).*?(?=</genre>)", 0);
        result.setEncounters(Stream.concat(genres.stream(), results.stream()).collect(Collectors.toList()));
        result.setCurrentEntry(entry.getName());
        result.setCurrentFile(zip.getName().substring(zip.getName().lastIndexOf(File.separator)+1));
        result.setBookFullText(bookFullXml);

        return result;
    }

    private static List<SearchResult.SearchEncounter> searchInText(String bookText,
                                                                   String searchQueryRegularExpression,
                                                                   int contextSize) {
        List<SearchResult.SearchEncounter> result = new ArrayList<>();
        Pattern pattern = Pattern.compile(searchQueryRegularExpression);
        Matcher matcher = pattern.matcher(bookText);
        while (matcher.find()) {
            int start = matcher.start();
            int end = matcher.end();
            int matchLength = end - start;

            int contextStart = Math.max(0, start - contextSize / 2);
            int contextEnd = Math.min(bookText.length(), end + contextSize / 2);
            String context = bookText.substring(contextStart, contextEnd);

            result.add(new SearchResult.SearchEncounter(context, start - contextStart, matchLength));
        }
        return result;
    }

    private static String getBookRawText(Document document) throws XPathExpressionException {
        XPathFactory xPathFactory = XPathFactory.newInstance();
        XPath xpath = xPathFactory.newXPath();
        XPathExpression expr = xpath.compile("//text()");
        NodeList textNodes = (NodeList) expr.evaluate(document, XPathConstants.NODESET);
        StringBuilder content = new StringBuilder();
        for (int i = 0; i < textNodes.getLength(); i++) {
            content.append(textNodes.item(i).getNodeValue()).append(" ");
        }
        return content.toString().trim();
    }
}

package org.honeyseeker;

import org.w3c.dom.Document;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

import javax.swing.*;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import javax.xml.transform.stream.StreamSource;
import java.awt.*;
import java.awt.event.*;
import java.io.*;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

@SuppressWarnings("CallToPrintStackTrace")
public class Main extends JFrame implements KeyListener, Logger {
    private static final String STOP_BUTTON_TEXT = "<html><p align=\"center\"> STOP \uD83D\uDDD9 </p> </html>";
    private static final String START_BUTTON_TEXT = "<html><p align=\"center\"> NEXT \uD83D\uDD0E︎ </p> </html>";
    private final JButton nextButton = new JButton(START_BUTTON_TEXT);
    private final JButton previousButton = new JButton("<html><p align=\"center\"> &nbsp; \uD83D\uDD19 </p> </html>");
    private final JButton highlightButton = new JButton("<html><p align=\"center\"> &nbsp; \uD83D\uDC41 </p> </html>");
    private ArrayList<String> log = new ArrayList<>(
            Arrays.asList("-", "-", "-", "-", "-", "-", "PROGRAM STARTED")
    );
    private final JLabel searchLog = new JLabel(getLogText());
    private final JScrollPane logScrollPane = new JScrollPane(
            searchLog, JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED, JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED
    );

    private final JTextField folderField = new JTextField();
    private final JTextField fileField = new JTextField();
    private final JTextField entryField = new JTextField();
    private final JTextField searchQueryField = new JTextField();
    private final JLabel previewLabel = new JLabel();
    private final JScrollPane previewScrollPane = new JScrollPane(
            previewLabel, JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED, JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED
    );
    private final Config config = new Config();
    private final ArrayList<JComponent> allUiControls = new ArrayList<>(Arrays.asList(
            previousButton, highlightButton, searchLog, folderField, fileField, entryField, searchQueryField,
            previewLabel
    ));
    private final Searcher searcher = new Searcher(this);
    private boolean highlightEnabled = true;

    private Main(){
        super("HoneySeeker");
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);

        addKeyListener(this);
        setFocusable(true);
        setFocusTraversalKeysEnabled(false);

        addComponentsToForm();
        addListeners();
        loadConfig();

        setSize(640, 480);
    }

    private void addListeners() {
        addWindowListener( new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent we) {
                saveConfig();
            }
        } );

        nextButton.addActionListener(this::processNextButton);
        previousButton.addActionListener(this::processBackButton);
        highlightButton.addActionListener(this::toggleHighlight);
        previewLabel.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                openLocally();
            }
        });
        searchLog.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                openAtFlibusta();
            }
        });
        for (JComponent component: allUiControls) {
            component.addKeyListener(this);
        }
    }

    private void openLocally() {
        if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
            try {
                SearchResult lastResult = searcher.getLastResult();
                if (lastResult.getEncounters().isEmpty()) {
                    return;
                }
                String bookName = lastResult.getCurrentEntry();
                String bookFullXml = lastResult.getBookFullText();

                // Get stylesheet from resources (works both in IDE and JAR)
                InputStream stylesheetStream = getClass().getResourceAsStream("/fb22htmls.xsl");
                if (stylesheetStream == null) {
                    throw new IOException("Stylesheet not found in resources");
                }

                try (StringReader stringReader = new StringReader(bookFullXml);
                     BufferedReader reader = new BufferedReader(stringReader)) {
                    DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
                    DocumentBuilder builder = factory.newDocumentBuilder();
                    Document document = builder.parse(new InputSource(reader));

                    StreamSource styleSource = new StreamSource(stylesheetStream);
                    TransformerFactory transformerFactory = TransformerFactory.newInstance();
                    Transformer transformer = transformerFactory.newTransformer(styleSource);

                    // Create HTML file in Downloads folder
                    String downloadsPath = System.getProperty("user.home") +
                            File.separator + "Downloads" + File.separator;
                    String safeBookName = bookName.replaceAll("[^a-zA-Z0-9.-]", "_");
                    File htmlFile = new File(downloadsPath + safeBookName + ".html");

                    // Transform XML to HTML and save to file
                    try (FileOutputStream output = new FileOutputStream(htmlFile)) {
                        StreamResult result = new StreamResult(output);
                        transformer.transform(new DOMSource(document), result);
                    }

                    // Open in browser
                    Desktop.getDesktop().browse(htmlFile.toURI());
                }

            } catch (IOException | ParserConfigurationException | SAXException | TransformerException e) {
                log("error opening book: " + e);
                e.printStackTrace();
            }
        }
    }

    private void openAtFlibusta() {
        if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
            try {
                String url = "https://flibusta.is/b/" + config.getCurrentEntry().replace(".fb2", "");
                Desktop.getDesktop().browse(new URI(url));
            } catch (IOException | URISyntaxException e) {
                log("error opening book: " + e);
                e.printStackTrace();
            }
        }
    }

    private void toggleHighlight(ActionEvent actionEvent) {
        highlightEnabled = !highlightEnabled;
        searchQueryField.setVisible(highlightEnabled);
        setPreviewText(searcher.getLastResult());
    }

    private void processSearch(boolean isBackwards) {
        if (nextButton.getText().contains("STOP")) {
            searcher.shouldStop = true;
            return;
        }
        for (JComponent component: allUiControls) {
            component.setEnabled(false);
            nextButton.setText(STOP_BUTTON_TEXT);
        }
        updateConfigFromFields();
        new Thread(() -> {
            try {
                SearchResult result = searcher.doSearch(config, isBackwards);
                if (!result.getEncounters().isEmpty()) {
                    config.setCurrentFile(result.getCurrentFile());
                    config.setCurrentEntry(result.getCurrentEntry());
                }
            } catch (SearcherException e) {
                log("error: " + e.getMessage());
                e.printStackTrace();
                config.setCurrentFile(e.getCurrentFile());
                config.setCurrentEntry(e.getCurrentEntry());
            }
            SwingUtilities.invokeLater(() -> {
                updateFieldsFromConfig();
                setPreviewText(searcher.getLastResult());
                for (JComponent component: allUiControls) {
                    component.setEnabled(true);
                    nextButton.setText(START_BUTTON_TEXT);
                }
            });
        }).start();
    }

    private void processNextButton(ActionEvent noop) {
        processSearch(false);
    }

    private void processBackButton(ActionEvent noop) {
        processSearch(true);
    }

    private void setPreviewText(SearchResult result) {
        String body = String.join(
                "<br> <b> =============== </b> <br>",
                result.getEncounters().stream()
                        .map(this::highlight)
                        .toList()
        );
        String text = "<html> <body style='width: " + getWidth()/10*7 +"px'> " + body + "</body> </html>";
        previewLabel.setText(text);
        previewScrollPane.updateUI();
    }

    private String highlight(SearchResult.SearchEncounter encounter) {
        if (!highlightEnabled) {
            return encounter.getContext();
        }

        String before = encounter.getContext().substring(0, encounter.getTargetPosition());
        String selected = encounter.getContext().substring(
                encounter.getTargetPosition(), encounter.getTargetPosition() + encounter.getTargetLen()
        );
        String after = encounter.getContext().substring(encounter.getTargetPosition() + encounter.getTargetLen());

        return before + "<b>" + selected + "</b>" + after;
    }

    private void saveConfig() {
        updateConfigFromFields();
        log(config.save());
    }

    private void updateConfigFromFields() {
        config.setFolder(folderField.getText());
        config.setCurrentFile(fileField.getText());
        config.setCurrentEntry(entryField.getText());
        config.setSearchQuery(searchQueryField.getText());
    }

    private void loadConfig() {
        log(config.load());
        updateFieldsFromConfig();
    }

    private void updateFieldsFromConfig() {
        folderField.setText(config.getFolder());
        fileField.setText(config.getCurrentFile());
        entryField.setText(config.getCurrentEntry());
        searchQueryField.setText(config.getSearchQuery());
    }

    @SuppressWarnings({"ReassignedVariable", "UnusedAssignment"})
    private void addComponentsToForm() {
        Main pane = this;

        pane.setLayout(new GridBagLayout());
        GridBagConstraints c = new GridBagConstraints();

        int gridy = 0;
        for (JTextField field: Arrays.asList(folderField, fileField, entryField, searchQueryField)) {
            c.fill = GridBagConstraints.HORIZONTAL;
            c.weightx = 0.0;
            c.weighty = 0.0;
            c.gridx = 0;
            c.gridy = gridy++;
            c.gridwidth = 3;
            pane.add(field, c);
        }
        c.fill = GridBagConstraints.BOTH;
        c.weightx = 0.0;
        c.weighty = 0.8;
        c.gridx = 0;
        c.gridy = gridy++;
        c.gridwidth = 3;

        pane.add(previewScrollPane, c);
        previewLabel.setText("<html> <body style='width: %1spx'> click \"find\" to start search <br>" +
                        String.join("<br>", Collections.nCopies(20,"")) +
                        "</body> </html>"
        );
        previewLabel.setFont(new Font(previewLabel.getFont().getName(), Font.PLAIN, 24));

        {
            c.fill = GridBagConstraints.HORIZONTAL;
            c.weightx = 1.0;
            c.weighty = 0.0;
            c.gridx = 0;
            c.gridy = gridy;
            c.gridwidth = 1;
            pane.add(nextButton, c);

            c.fill = GridBagConstraints.NONE;
            c.weightx = 0.0;
            c.weighty = 0.0;
            c.gridx = 1;
            c.gridy = gridy;
            c.gridwidth = 1;
            pane.add(previousButton, c);

            c.fill = GridBagConstraints.NONE;
            c.weightx = 0.0;
            c.weighty = 0.0;
            c.gridx = 2;
            c.gridy = gridy++;
            c.gridwidth = 1;
            pane.add(highlightButton, c);
        }

        c.fill = GridBagConstraints.BOTH;
        c.weightx = 0.0;
        c.weighty = 0.2;
        c.gridx = 0;
        c.gridy = gridy++;
        c.gridwidth = 3;
        pane.add(logScrollPane, c);
    }

    private String getLogText() {
        log = new ArrayList<>(log.subList(Math.max(log.size() - 100, 0), log.size()));
        return "<html>" + String.join("<br> ", log) + "<html>";
    }

    @Override
    public void log(List<String> newLogEntries){
        for (String logEntry: newLogEntries) {
            System.out.println(logEntry);
        }
        this.log.addAll(newLogEntries);
        SwingUtilities.invokeLater(() -> {
            searchLog.setText(getLogText());
            JScrollBar vertical = logScrollPane.getVerticalScrollBar();
            vertical.setValue(vertical.getMaximum());
        });
    }

    @Override
    public void log(String line) {
        log(List.of(line));
    }

    public static void main(String[] args) {
        Main app = new Main();
        app.setVisible(true);
    }

    @Override
    public void keyTyped(KeyEvent e) {}

    @Override
    public void keyPressed(KeyEvent e) {}

    @Override
    public void keyReleased(KeyEvent keyEvent) {
        if (keyEvent.getKeyCode() == KeyEvent.VK_ENTER && keyEvent.isControlDown()) {
            processNextButton(null);
        }
    }
}
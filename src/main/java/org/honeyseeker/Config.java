package org.honeyseeker;

import lombok.Data;
import org.ini4j.Ini;

import java.io.File;
import java.net.URISyntaxException;

@SuppressWarnings({"CallToPrintStackTrace", "MismatchedQueryAndUpdateOfCollection"})
@Data
public class Config {
    private String folder = "C:\\folder\\to\\search";
    private String currentFile = "123-456.zip";
    private String currentEntry = "1.fb2";
    private String searchQuery = "text to search";

    @SuppressWarnings("ResultOfMethodCallIgnored")
    public String save() {
        try {
            File iniFile = new File(getPathToJar() + File.separator + "honey_seeker.ini");
            iniFile.createNewFile();
            Ini ini = new Ini(iniFile);
            ini.put("general", "folder", folder);
            ini.put("general", "file", currentFile);
            ini.put("general", "entry", currentEntry);
            ini.put("general", "query", searchQuery);
            ini.store();
            return "saved config";
        } catch (Exception e) {
            e.printStackTrace();
            return "error saving config";
        }
    }

    public String load() {
        try {
            Ini ini = new Ini(new File(getPathToJar() + File.separator + "honey_seeker.ini"));
            folder = ini.get("general", "folder");
            currentFile = ini.get("general", "file");
            currentEntry = ini.get("general", "entry");
            searchQuery = ini.get("general", "query");
            return "loaded config";
        } catch (Exception e) {
            e.printStackTrace();
            return "error loading config";
        }
    }

    private String getPathToJar() throws URISyntaxException {
        return new File(
                Config.class.getProtectionDomain().getCodeSource().getLocation().toURI()
        ).getParentFile().getPath();
    }
}
